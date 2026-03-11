package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.RedisConstant;
import com.sky.context.BaseContext;
import com.sky.dto.*;
import com.sky.entity.*;
import com.sky.enums.OrderEvent;
import com.sky.enums.OrderStatus;
import com.sky.exception.BusinessException;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.*;
import com.sky.result.PageResult;
import com.sky.service.OrderService;
import com.sky.service.IdempotentService;
import com.sky.service.OrderTimeoutCloseService;
import com.sky.service.state.OrderStateContext;
import com.sky.utils.DistanceUtil;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;
import com.sky.websocket.WebSocketServer;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class OrderServiceImpl implements OrderService {

    /**
     * 下单分布式锁前缀。
     * 组合 userId + cartDigest 后可以把锁粒度控制在“同用户同购物车快照”。
     */
    private static final String ORDER_SUBMIT_LOCK_PREFIX = "order:submit:lock:";

    /**
     * 支付相关分布式锁前缀。
     * payment 与 callback 统一使用该前缀，确保同一订单的支付链路串行化。
     */
    private static final String ORDER_PAY_LOCK_PREFIX = "order:pay:lock:";

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private AddressMapper addressMapper;

    @Autowired
    private ShoppingCartMapper shoppingCartMapper;

    @Autowired
    private OrderDetailMapper orderDetailMapper;

    @Autowired
    private StateMachineFactory<OrderStatus, OrderEvent> stateMachineFactory;

    @Autowired
    private OrderStateContext orderStateContext;

    @Autowired
    private DishMapper dishMapper;

    @Autowired
    private SetMealMapper setMealMapper;

    @Autowired
    private DistanceUtil distanceUtil;

    @Value("${sky.shop.limit-distance}")
    private Double limitDistance;

    @Autowired
    private WebSocketServer webSocketServer;

    @Autowired
    private IdempotentService idempotentService;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private OrderRequestLogMapper orderRequestLogMapper;

    @Autowired
    private PaymentTxnMapper paymentTxnMapper;

    @Autowired
    private OrderTimeoutCloseService orderTimeoutCloseService;

    @Override
    @Transactional
    public OrderSubmitVO submit(OrdersSubmitDTO ordersSubmitDTO) {

        // 当前登录用户ID（由登录拦截器写入 BaseContext）。
        Long userId = BaseContext.getCurrentId();
        // 前端携带的一次性提交令牌。
        String submitToken = ordersSubmitDTO.getToken();

        // 1) 先查数据库幂等日志（最终兜底层）。
        //    目的：即便 Redis token 已经过期/丢失，也能识别是否已经成功下过单，查询这个token对应的请求是否处理过了，拿到处理结果。
        OrderRequestLog existingLog = orderRequestLogMapper.getByUserIdAndSubmitToken(userId, submitToken);
        if (existingLog != null) { //幂等日志已经存在这条订单记录
            if (Objects.equals(existingLog.getStatus(), OrderRequestLog.SUCCESS)) {
                // 幂等命中成功：直接返回已有订单信息，不再重复创建。
                return rebuildSubmitVO(userId, existingLog);
            }
            else if (Objects.equals(existingLog.getStatus(), OrderRequestLog.PROCESSING)) {
                // 另一个线程正在处理，提示稍后重试。
                throw new BusinessException("订单正在处理中，请稍后再试");
            }
            // 已失败：让用户重新获取 token 再提交，避免使用旧 token 无限重试。
            else{
                throw new BusinessException("订单提交失败，请重新获取提交令牌");
            }
        }

        // 2) 再校验并删除 Redis token（入口层快速防重）。
        //    delete 成功 -> 本次请求拿到“唯一处理权”；
        //    delete 失败 -> token 不存在（重复提交或过期）。
        // 进入页面的时候调用getSubmitToken去给这个userid生成幂等token存于redis
        // 查看这次请求现在还有没有资格进入订单的业务“处理”。可能还有其他线程正在处理。
        boolean valid = idempotentService.validateAndRemoveSubmitToken(userId, submitToken);
        if (!valid) {
            // 极端并发下可能是“第一次请求刚成功，就是下单成了，但前端重试，又发了一次请求”：
            // 再查一次 DB 幂等日志，如果已成功则返回同一结果，即订单下单成功。

            OrderRequestLog replayLog = orderRequestLogMapper.getByUserIdAndSubmitToken(userId, submitToken);
            if (replayLog != null && Objects.equals(replayLog.getStatus(), OrderRequestLog.SUCCESS)) {
                return rebuildSubmitVO(userId, replayLog);
            }
            throw new BusinessException("提交令牌已失效，请刷新页面重试");
        }

        // 3) 生成购物车摘要，用于形成更细粒度锁。
        String cartDigest = idempotentService.generateCartDigest(userId);

        // 4) 抢下单锁：避免同一时刻同用户相同购物车被并发处理。
        // 同一个用户+购物车快照作为key，getlock用来获取这个分布式锁的对象，而不是真正的加锁。
        // 这是对当前购物车的订单的隔离性
        String lockKey = ORDER_SUBMIT_LOCK_PREFIX + userId + ":" + cartDigest;
        RLock lock = redissonClient.getLock(lockKey); //锁对象
        if (!lock.tryLock()) { // 如果没有别的进程持有这把lockKey锁，就加锁返回true；否则返回false，那就是有进程在拿这把锁
            throw new BusinessException("订单正在处理中，请稍后再试");
        }

        // 准备“处理中”幂等日志，后续成功/失败都基于这条记录更新状态。
        OrderRequestLog requestLog = new OrderRequestLog();
        requestLog.setUserId(userId);
        requestLog.setSubmitToken(submitToken);
        requestLog.setCartDigest(cartDigest);
        requestLog.setStatus(OrderRequestLog.PROCESSING);
        requestLog.setCreateTime(LocalDateTime.now());
        requestLog.setUpdateTime(LocalDateTime.now());

        try {
            // 插入 processing 日志。若并发插入同一 token，DB 唯一键会抛异常。
            orderRequestLogMapper.insert(requestLog);
        } catch (DuplicateKeyException ex) {
            // 并发分支：另一个线程可能已经处理完成，这里尝试直接复用结果。
            OrderRequestLog duplicatedLog = orderRequestLogMapper.getByUserIdAndSubmitToken(userId, submitToken);
            if (duplicatedLog != null && Objects.equals(duplicatedLog.getStatus(), OrderRequestLog.SUCCESS)) {
                return rebuildSubmitVO(userId, duplicatedLog);
            }
            throw new BusinessException("订单正在处理中，请稍后再试");
        }

        try {
            // 5) 校验购物车不能为空（业务前置校验）。
            List<ShoppingCart> shoppingCartList = shoppingCartMapper.listShoppingCart(userId);
            if (CollectionUtils.isEmpty(shoppingCartList)) {
                throw new BusinessException("购物车为空，无法下单");
            }

            // 6) 校验收货地址存在且属于当前用户。
            AddressBook address = addressMapper.getAddressById(userId, ordersSubmitDTO.getAddressBookId());
            if (address == null) {
                throw new BusinessException("地址不存在");
            }

            // 7) 校验配送距离。
            Double distance = distanceUtil.getDistance(address.detailedAddress());
            if (distance > limitDistance) {
                throw new OrderBusinessException("下单失败，超出配送范围");
            }

            // 8) 校验店铺营业状态（Redis 缓存读取）。
            Integer shopStatus = (Integer) redisTemplate.opsForValue().get(RedisConstant.SHOP_STATUS_KEY);
            if (shopStatus != null && shopStatus == 0){
                throw new OrderBusinessException("下单失败，店铺不在营业中");
            }

            // 9) 组装订单主表对象。
            Orders orders = new Orders();
            BeanUtils.copyProperties(ordersSubmitDTO, orders);
            // 设置收货人姓名
            orders.setConsignee(address.getConsignee());
            // 设置收货人号码
            orders.setPhone(address.getPhone());
            // 设置收货人地址
            orders.setAddress(address.getProvinceName() + address.getCityName() + address.getDistrictName() + address.getDetail());
            // 设置用户ID
            orders.setUserId(userId);
            // 设置订单号
            String orderNumber = createOrderNumber(userId);
            orders.setNumber(orderNumber);
            // 设置下单时间
            LocalDateTime orderTime = LocalDateTime.now();
            orders.setOrderTime(orderTime);
            // 设置订单总金额
            // 说明：这里按“购物车商品金额 + 打包费 + 固定配送费(6元)”计算。
            BigDecimal orderAmount = countAmount(shoppingCartList)
                    .add(BigDecimal.valueOf(ordersSubmitDTO.getPackAmount()))
                    .add(BigDecimal.valueOf(6));
            orders.setAmount(orderAmount);

            // 10) 写订单主表。
            orderMapper.insert(orders);

            // 11) 写订单明细表。
            insertOrderDetail(shoppingCartList, orders.getId());

            // 12) 清空购物车（该动作与订单创建在同事务内）。
            shoppingCartMapper.clearShoppingCart(userId);

            // 13) 幂等日志置为成功，并绑定订单ID/订单号用于重复请求回放。
            requestLog.setStatus(OrderRequestLog.SUCCESS);
            requestLog.setOrderId(orders.getId());
            requestLog.setOrderNumber(orderNumber);
            requestLog.setUpdateTime(LocalDateTime.now());
            orderRequestLogMapper.updateById(requestLog);

            // 14) 事务提交成功后投递超时关单任务，避免回滚产生脏延时消息。
            orderTimeoutCloseService.enqueueAfterCommit(orders);

            // 15) 返回下单结果给前端。
            return OrderSubmitVO.builder()
                    .id(orders.getId())
                    .orderAmount(orderAmount)
                    .orderNumber(orderNumber)
                    .orderTime(orderTime)
                    .build();
        } catch (RuntimeException ex) {
            // 失败分支：最佳努力写失败原因（便于排障）。
            // 注意：该更新与事务同生共死，若外层事务回滚，这里也会回滚。
            if (requestLog.getId() != null) {
                requestLog.setStatus(OrderRequestLog.FAIL);
                requestLog.setFailReason(truncate(ex.getMessage(), 255));
                requestLog.setUpdateTime(LocalDateTime.now());
                orderRequestLogMapper.updateById(requestLog);
            }
            throw ex;
        } finally {
            // 16) 无论成功失败都释放锁，防止死锁。
            if (lock != null && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void insertOrderDetail(List<ShoppingCart> shoppingCartList, Long orderId) {
        List<OrderDetail> orderDetailList = shoppingCartList.stream().map(shoppingCart -> {
            OrderDetail orderDetail = new OrderDetail();
            BeanUtils.copyProperties(shoppingCart, orderDetail);
            orderDetail.setOrderId(orderId);
            return orderDetail;
        }).collect(Collectors.toList());
        orderDetailMapper.insertBatch(orderDetailList);
    }

    private String createOrderNumber(Long userId) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmssSSS");
        String timestamp = sdf.format(new Date());
        int randomNum = new Random().nextInt(900) + 100; // 100-999三位随机数
        return timestamp + userId.toString() + randomNum;
    }

    private BigDecimal countAmount( List<ShoppingCart> shoppingCartList) {
        return shoppingCartList.stream()
                .reduce(new BigDecimal("0"),  // 初始值
                        (subtotal, cart) -> subtotal.add(cart.getAmount().multiply(BigDecimal.valueOf(cart.getNumber()))), // 累加函数
                        BigDecimal::add);  // combiner，串行流时不重要
    }

    /**
     * 订单支付
     *
     * @param ordersPaymentDTO
     * @return
     */
    public void payment(OrdersPaymentDTO ordersPaymentDTO) {
        // 当前登录用户ID。
        Long userId = BaseContext.getCurrentId();
        // 本次支付对应订单号。
        String orderNumber = ordersPaymentDTO.getOrderNumber();

        // 1) 获取订单支付锁（与 callback 共用同一锁域）。
        String lockKey = ORDER_PAY_LOCK_PREFIX + orderNumber;
        RLock lock = redissonClient.getLock(lockKey);
        if (!lock.tryLock()) {
            throw new BusinessException("支付处理中，请稍后再试");
        }
        
        try {
            // 2) 校验订单存在且属于当前用户。
            Orders order = orderMapper.getOrderByOrderNumber(userId, orderNumber);
            if (order == null) {
                throw new OrderBusinessException("订单不存在");
            }

            // 3) 只有待支付订单才允许发起支付。
            if (!Objects.equals(order.getStatus(), Orders.PENDING_PAYMENT)) {
                throw new OrderBusinessException("该订单已支付");
            }

            // 4) 只做“支付发起幂等”，不在这里推进订单状态。
            //    订单状态流转必须以支付回调确认为准，避免前端重复调用误改状态。
            //    PaymentTxn订单流水表，记录支付状态，回调信息
            PaymentTxn existingPayment = paymentTxnMapper.getByOrderNumber(orderNumber);
            if (existingPayment != null) {
                if (Objects.equals(existingPayment.getStatus(), PaymentTxn.SUCCESS)) {
                    // 已成功支付，不再重复发起。
                    throw new OrderBusinessException("该订单已支付");
                }
                else if (Objects.equals(existingPayment.getStatus(), PaymentTxn.WAITING)) {
                    // 已有待回调记录，直接幂等返回。
                    log.info("订单已存在待处理支付请求，orderNumber={}, payRequestId={}", orderNumber, existingPayment.getPayRequestId());
                    return;
                }
                else{
                    // 失败可重试：复用历史流水，并刷新支付请求ID。
                    existingPayment.setPayRequestId(UUID.randomUUID().toString().replace("-", ""));
                    existingPayment.setPayMethod(ordersPaymentDTO.getPayMethod());
                    existingPayment.setStatus(PaymentTxn.WAITING);
                    existingPayment.setFailReason(null);
                    existingPayment.setRequestTime(LocalDateTime.now());
                    existingPayment.setUpdateTime(LocalDateTime.now());
                    paymentTxnMapper.updateById(existingPayment);
                    return;
                }

            }

            PaymentTxn paymentTxn = new PaymentTxn();
            paymentTxn.setPayRequestId(UUID.randomUUID().toString().replace("-", ""));
            paymentTxn.setOrderNumber(orderNumber);
            paymentTxn.setUserId(userId);
            paymentTxn.setPayAmount(order.getAmount());
            paymentTxn.setPayMethod(ordersPaymentDTO.getPayMethod());
            paymentTxn.setStatus(PaymentTxn.WAITING);
            paymentTxn.setRequestTime(LocalDateTime.now());
            paymentTxn.setCreateTime(LocalDateTime.now());
            paymentTxn.setUpdateTime(LocalDateTime.now());
            paymentTxnMapper.insert(paymentTxn);
        } finally {
            // 5) 释放锁。
            if (lock != null && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public PageResult<OrderVO> getHistoryOrders(OrdersPageQueryDTO ordersPageQueryDTO) {
        Page<OrderVO> orderVOPage = PageHelper.startPage(ordersPageQueryDTO.getPage(), ordersPageQueryDTO.getPageSize())
                .doSelectPage(() -> orderMapper.getHistoryOrders(ordersPageQueryDTO));
        return new PageResult<>(orderVOPage.getTotal(), orderVOPage.getResult(), ordersPageQueryDTO.getPage(), orderVOPage.getPageNum());
    }

    @Override
    public OrderVO getUserOrderDetail(Long id) {
        Long userId = BaseContext.getCurrentId();
        Orders order = orderMapper.getOrderByOrderIdAndUserId(userId, id);
        if (order == null) {
            throw new OrderBusinessException("订单不存在");
        }
        List<OrderDetail> orderDetailList = orderDetailMapper.getOrderDetailByOrderId(id);
        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(order, orderVO);
        orderVO.setOrderDetailList(orderDetailList);
        return orderVO;
    }

    @Override
    public void repeatOrder(Long id) {
        List<OrderDetail> orderDetailList = orderDetailMapper.getOrderDetailByOrderId(id);
        if (CollectionUtils.isEmpty(orderDetailList)) {
            throw new OrderBusinessException("订单不存在");
        }

        // 如果存在则重新加入购物车，同时需要判断加入购物车的菜品或者套餐是否存在或者已经停售
        List<Long> dishIdList = new ArrayList<>();
        List<Long> setmealIdList = new ArrayList<>();
        for (OrderDetail orderDetail : orderDetailList) {
            Long dishId = orderDetail.getDishId();
            if (dishId != null) {
                dishIdList.add(dishId);
            } else {
                setmealIdList.add(orderDetail.getSetmealId());
            }
        }
        List<Long> sellingDishIds = new ArrayList<>();
        List<Long> sellingSetMealIds = new ArrayList<>();
        if (!CollectionUtils.isEmpty(dishIdList)) {  // 如果菜品数量本来就是0，就没必要去查数据库了
            sellingDishIds = dishMapper.getSellingDishListByIds(dishIdList);
        }
        if (!CollectionUtils.isEmpty(setmealIdList)) {  // 如果套餐数量本来就是0，就没必要去查数据库了
            sellingSetMealIds = setMealMapper.getSellingSetMealByIds(setmealIdList);
        }

        if (sellingDishIds.size() != dishIdList.size() || setmealIdList.size() != sellingSetMealIds.size()) {
            throw new OrderBusinessException("存在菜品或套餐下架，无法重新创建购物车");
        }

        List<ShoppingCart> shoppingCartList = new ArrayList<>();
        for (OrderDetail orderDetail : orderDetailList) {
            ShoppingCart shoppingCart = new ShoppingCart();
            BeanUtils.copyProperties(orderDetail, shoppingCart);
            shoppingCart.setId(null);
            shoppingCart.setCreateTime(LocalDateTime.now());
            shoppingCart.setUserId(BaseContext.getCurrentId());
            shoppingCartList.add(shoppingCart);
        }
        shoppingCartMapper.saveItemBatch(shoppingCartList);
    }

    @Override
    public void userCancelOrder(Long id) {
        // 涉及到状态流转，所以需要使用状态机
        Long userId = BaseContext.getCurrentId();
        Orders order = orderMapper.getOrderByOrderIdAndUserId(userId, id);
        if (order == null) {
            throw new OrderBusinessException("订单不存在");
        }

//        Integer orderStatus = order.getStatus();
//        if (
//                !Objects.equals(orderStatus, Orders.PENDING_PAYMENT)
//                && !Objects.equals(orderStatus, Orders.TO_BE_CONFIRMED)
//                && !Objects.equals(orderStatus, Orders.CANCELLED)
//        ) {
//            throw new OrderBusinessException("当前订单状态不支持用户取消");
//        }
        // 创建状态机对象
        StateMachine<OrderStatus, OrderEvent> stateMachine = buildOrderStateMachine(order);
        orderStateContext.userCancel(order, stateMachine);  // 区分是后台取消还是用户取消，就不需要判断状态了
    }

    @Override
    public PageResult<OrderVO> getOrderListByCondition(OrdersPageQueryDTO ordersPageQueryDTO) {
        Page<OrderVO> orderVOPage = PageHelper.startPage(ordersPageQueryDTO.getPage(), ordersPageQueryDTO.getPageSize())
                .doSelectPage(() -> orderMapper.getHistoryOrders(ordersPageQueryDTO));

        // 需要添加菜品信息
        List<OrderVO> voList = orderVOPage.getResult();
        voList.forEach(orderVO -> {
            List<OrderDetail> orderDetailList = orderVO.getOrderDetailList();
            StringBuilder orderDishes = new StringBuilder();
            for (OrderDetail detail : orderDetailList) {
                orderDishes.append(detail.getName())
                        .append("*")
                        .append(detail.getNumber())
                        .append(";");
            }
            orderVO.setOrderDishes(orderDishes.toString());
        });

        return new PageResult<>(orderVOPage.getTotal(), orderVOPage.getResult(), orderVOPage.getPageSize(), orderVOPage.getPageNum());
    }

    @Override
    public OrderVO getOrderDetail(Long id) {
        Orders order = orderMapper.getOrderByOrderId(id);
        if (order == null) {
            throw new OrderBusinessException("订单不存在");
        }
        List<OrderDetail> orderDetailList = orderDetailMapper.getOrderDetailByOrderId(id);
        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(order, orderVO);
        orderVO.setOrderDetailList(orderDetailList);
        return orderVO;
    }

    @Override
    public void confirmOrder(Long id) {
        Orders order = orderMapper.getOrderByOrderId(id);
        if (order == null) {
            throw new OrderBusinessException("订单不存在");
        }
        // 创建状态机对象
        StateMachine<OrderStatus, OrderEvent> stateMachine = buildOrderStateMachine(order);
        orderStateContext.confirmOrder(order, stateMachine);
    }

    @Override
    public OrderStatisticsVO statistics() {
        return orderMapper.statistics();
    }

    @Override
    public void rejectOrder(OrdersRejectionDTO ordersRejectionDTO) {
        Orders order = orderMapper.getOrderByOrderId(ordersRejectionDTO.getId());
        if (order == null) {
            throw new OrderBusinessException("订单不存在");
        }
        // 创建状态机对象
        StateMachine<OrderStatus, OrderEvent> stateMachine = buildOrderStateMachine(order);
        orderStateContext.adminCancel(order, stateMachine, ordersRejectionDTO.getRejectionReason());
    }

    @Override
    public void adminCancelOrder(OrdersCancelDTO ordersCancelDTO) {
        Orders order = orderMapper.getOrderByOrderId(ordersCancelDTO.getId());
        if (order == null) {
            throw new OrderBusinessException("订单不存在");
        }
        // 创建状态机对象
        StateMachine<OrderStatus, OrderEvent> stateMachine = buildOrderStateMachine(order);
        orderStateContext.adminCancel(order, stateMachine, ordersCancelDTO.getCancelReason());
    }

    @Override
    public void deliveryOrder(Long id) {
        Orders order = orderMapper.getOrderByOrderId(id);
        if (order == null) {
            throw new OrderBusinessException("订单不存在");
        }
        // 创建状态机对象
        StateMachine<OrderStatus, OrderEvent> stateMachine = buildOrderStateMachine(order);
        orderStateContext.delivery(order, stateMachine);
    }

    @Override
    public void completeOrder(Long id) {
        Orders order = orderMapper.getOrderByOrderId(id);
        if (order == null) {
            throw new OrderBusinessException("订单不存在");
        }
        // 创建状态机对象
        StateMachine<OrderStatus, OrderEvent> stateMachine = buildOrderStateMachine(order);
        orderStateContext.complete(order, stateMachine);
    }

    @Override
    public void remindOrder(Long id) {
        Orders order = orderMapper.getOrderByOrderId(id);
        if (order == null) {
            throw new OrderBusinessException("订单不存在");
        }

        if (!Objects.equals(order.getStatus(), Orders.TO_BE_CONFIRMED)) {
            throw new OrderBusinessException("当前订单状态不支持催单");
        }

        // 发送WebSocket通知
        Map<String, Object> message = new HashMap<>();
        message.put("type", 2);
        message.put("orderId", order.getId());
        message.put("content", "订单号: " + order.getNumber());

        webSocketServer.sendToAllClient(JSON.toJSONString(message));
    }

    private StateMachine<OrderStatus, OrderEvent> buildOrderStateMachine(Orders order) {
        // 1) 按订单号获取状态机实例（同订单维度）。
        // 获取新的状态机实例
        StateMachine<OrderStatus, OrderEvent> stateMachine = stateMachineFactory.getStateMachine(order.getNumber());
        // 2) 先停止并重置到数据库中的真实状态，避免状态机内存状态与DB不一致。
        // 先停止，重置状态为订单当前状态
        stateMachine.stopReactively().block();
        stateMachine.getStateMachineAccessor()
                .doWithAllRegions(access -> access.resetStateMachineReactively(
                        new DefaultStateMachineContext<>(OrderStatus.fromState(order.getStatus()), null, null, null)
                ).block());
        // 3) 重置后再启动，进入可发送事件状态。
        stateMachine.startReactively().block();
        return stateMachine;
    }

    private OrderSubmitVO rebuildSubmitVO(Long userId, OrderRequestLog requestLog) {
        // 1) 根据幂等日志回放订单结果，用于重复提交时直接返回。
        Long orderId = requestLog.getOrderId();
        if (orderId == null) {
            throw new BusinessException("订单处理中，请稍后再试");
        }
        // 2) 防止越权：按 userId + orderId 查询。
        Orders order = orderMapper.getOrderByOrderIdAndUserId(userId, orderId);
        if (order == null) {
            throw new OrderBusinessException("订单不存在");
        }
        // 3) 组装与首次下单一致的返回结构。
        return OrderSubmitVO.builder()
                .id(order.getId())
                .orderAmount(order.getAmount())
                .orderNumber(order.getNumber())
                .orderTime(order.getOrderTime())
                .build();
    }

    private String truncate(String source, int maxLength) {
        // 防止异常文本过长写入数据库字段失败。
        if (!StringUtils.hasText(source)) {
            return null;
        }
        return source.length() <= maxLength ? source : source.substring(0, maxLength);
    }

}

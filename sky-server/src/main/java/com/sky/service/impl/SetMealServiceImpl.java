package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.cache.CacheKeyBuilder;
import com.sky.config.CacheProtectionProperties;
import com.sky.dto.SetmealDTO;
import com.sky.dto.SetmealPageQueryDTO;
import com.sky.entity.Category;
import com.sky.entity.Setmeal;
import com.sky.entity.SetmealDish;
import com.sky.exception.BusinessException;
import com.sky.mapper.CategoryMapper;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetMealDishMapper;
import com.sky.mapper.SetMealMapper;
import com.sky.result.PageResult;
import com.sky.service.BloomFilterService;
import com.sky.service.CacheInvalidationService;
import com.sky.service.CacheSupportService;
import com.sky.service.SetMealService;
import com.sky.utils.AliOssUtil;
import com.sky.vo.DishItemVO;
import com.sky.vo.SetmealVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SetMealServiceImpl implements SetMealService {

    @Autowired
    private SetMealMapper setMealMapper;

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private DishMapper dishMapper;

    @Autowired
    private SetMealDishMapper setMealDishMapper;

    @Autowired
    private AliOssUtil aliOssUtil;

    @Autowired
    private BloomFilterService bloomFilterService;

    @Autowired
    private CacheSupportService cacheSupportService;

    @Autowired
    private CacheInvalidationService cacheInvalidationService;

    @Autowired
    private CacheProtectionProperties cacheProtectionProperties;

    @Override
    @Transactional
    public boolean saveSetMeal(SetmealDTO setmealDTO) {
        // 先查找分类ID是否存在
        Category category = categoryMapper.getCategoryById(setmealDTO.getCategoryId());
        if (category == null) {
            throw new BusinessException("分类ID不存在");
        }

        // 套餐名称是否重复
        int count = setMealMapper.getByMealName(setmealDTO.getName());
        if (count != 0) {
            throw new BusinessException("套餐名称重复");
        }

        // 查找套餐下的菜品ID是否存在，并且要求是起售中
        List<SetmealDish> setmealDishes = setmealDTO.getSetmealDishes();
        checkDishExistAndSelling(setmealDishes);

        Setmeal setmeal = new Setmeal();
        BeanUtils.copyProperties(setmealDTO, setmeal);

        int affectRow = setMealMapper.saveSetMeal(setmeal);
        setmealDishes.forEach(setmealDish -> setmealDish.setSetmealId(setmeal.getId()));

        //保存关联菜品数据
        setMealDishMapper.saveSetmealDishBatch(setmealDishes);
        if (affectRow > 0) {
            bloomFilterService.addSetmealId(setmeal.getId());
            cacheInvalidationService.evictSetmealListByCategoryId(setmealDTO.getCategoryId());
            cacheInvalidationService.evictSetmealDishList(setmeal.getId());
        }

        return affectRow > 0;
    }

    @Override
    public PageResult<SetmealVO> listAllSetMeal(SetmealPageQueryDTO setmealPageQueryDTO) {
        Page<SetmealVO> setmealVOPage = PageHelper.startPage(setmealPageQueryDTO.getPage(), setmealPageQueryDTO.getPageSize())
                .doSelectPage(() -> setMealMapper.listAllSetMeal(setmealPageQueryDTO));
        return new PageResult<>(setmealVOPage.getTotal(), setmealVOPage.getResult(), setmealVOPage.getPageSize(), setmealVOPage.getPageNum());
    }

    @Override
    public SetmealVO getSetMealById(Long id) {
        SetmealVO setmealVO = setMealMapper.getSetMealById(id);
        if (setmealVO == null) {
            throw new BusinessException("套餐ID不存在");
        }
        return setmealVO;
    }

    @Override
    @Transactional
    public boolean updateSetMeal(SetmealDTO setmealDTO) {
        SetmealVO setmealVO = setMealMapper.getSetMealById(setmealDTO.getId());
        if (setmealVO == null) {
            throw new BusinessException("套餐ID不存在");
        }
        if (!setmealDTO.getName().equals(setmealVO.getName())) {
            int count = setMealMapper.getByMealName(setmealDTO.getName());
            if (count != 0) {
                throw new BusinessException("套餐名称重复");
            }
        }

        Setmeal setmeal = new Setmeal();
        BeanUtils.copyProperties(setmealDTO, setmeal);

        if (!setmealDTO.getImage().equals(setmealVO.getImage())) {
            // 如果上传的图片路径不同，需要删除原来的oss图片
            aliOssUtil.deleteFile(setmealVO.getImage());
        }

        int affectRow = setMealMapper.updateSetMeal(setmeal);

        // 更新套餐菜品关系
        // 删除原来的套餐菜品
        setMealDishMapper.delSetMealDishById(setmealDTO.getId());
        // 新增套餐菜品, 菜品列表不可能为空
        // 查找套餐下的菜品ID是否存在，并且要求是起售中
        List<SetmealDish> setmealDishes = setmealDTO.getSetmealDishes();
        checkDishExistAndSelling(setmealDishes);
        setmealDishes.forEach(setmealDish -> setmealDish.setSetmealId(setmeal.getId()));
        setMealDishMapper.saveSetmealDishBatch(setmealDishes);
        cacheInvalidationService.evictSetmealListByCategoryId(setmealVO.getCategoryId());
        cacheInvalidationService.evictSetmealListByCategoryId(setmealDTO.getCategoryId());
        cacheInvalidationService.evictSetmealDishList(setmealDTO.getId());
        return affectRow > 0;
    }

    @Override
    public boolean updateSetMealStatus(Long id, Integer status) {
        SetmealVO setmealVO = setMealMapper.getSetMealById(id);
        if (setmealVO == null) {
            throw new BusinessException("套餐ID不存在");
        }
        Setmeal setmeal = new Setmeal();
        BeanUtils.copyProperties(setmealVO, setmeal);
        setmeal.setStatus(status);
        int affectRow = setMealMapper.updateSetMeal(setmeal);
        cacheInvalidationService.evictSetmealListByCategoryId(setmealVO.getCategoryId());
        cacheInvalidationService.evictSetmealDishList(id);
        return affectRow > 0;
    }

    @Override
    public boolean deleteSetMealByIds(List<Long> ids) {
        // 查找处于非起售状态的套餐
        List<Long> sellingSetMealIds = setMealMapper.getSellingSetMealByIds(ids);
        List<Long> notSellingSetMealIds = ids.stream().filter(aLong -> !sellingSetMealIds.contains(aLong)).collect(Collectors.toList());
        List<Long> categoryIds = new ArrayList<>();
        if (!CollectionUtils.isEmpty(notSellingSetMealIds)) {
            categoryIds = setMealMapper.getCategoryIdsBySetmealIds(notSellingSetMealIds);
        }

        int affectRows = 0;
        if (!CollectionUtils.isEmpty(notSellingSetMealIds)) {

            List<String> images = setMealMapper.getSetMealImagesByIds(notSellingSetMealIds);
            aliOssUtil.deleteFileBatch(images);
            // 删除套餐
            affectRows += setMealMapper.deleteSetMealByIds(notSellingSetMealIds);
            // 删除套餐关联菜品
            setMealDishMapper.delSetMealDishByIds(notSellingSetMealIds);
        }
        cacheInvalidationService.evictSetmealListByCategoryIds(categoryIds);
        cacheInvalidationService.evictSetmealDishLists(notSellingSetMealIds);
        return affectRows > 0;
    }

    @Override
    public List<Setmeal> getSetMealListByCategoryId(Long categoryId) {
        // 先做 Bloom 过滤：分类ID若明确不存在，直接返回空集合，拦截穿透流量。
        if (!bloomFilterService.mightContainCategory(categoryId)) {
            return new ArrayList<>();
        }
        // 再走缓存读取：未命中才回源数据库并回填缓存。
        return cacheSupportService.getOrLoadList(
                CacheKeyBuilder.setmealListByCategoryKey(categoryId),
                Setmeal.class,
                cacheProtectionProperties.getTtl().getSetmealListSeconds(),
                () -> setMealMapper.getSetMealListByCategoryId(categoryId)
        );
    }

    @Override
    public List<DishItemVO> getDishListBySetMealId(Long id) {
        // 套餐ID同样先走 Bloom 判断，避免非法ID造成的穿透查询。
        if (!bloomFilterService.mightContainSetmeal(id)) {
            return new ArrayList<>();
        }
        // 命中缓存直接返回；未命中回源并回填，空结果也会短TTL缓存。
        return cacheSupportService.getOrLoadList(
                CacheKeyBuilder.setmealDishListKey(id),
                DishItemVO.class,
                cacheProtectionProperties.getTtl().getSetmealDishSeconds(),
                () -> setMealDishMapper.getDishListBySetMealId(id)
        );
    }

    private void checkDishExistAndSelling(List<SetmealDish> setmealDishes) {
        Set<Long> dishIds = setmealDishes.stream().map(SetmealDish::getDishId).collect(Collectors.toSet());
        List<Long> sellingDishListByIds = dishMapper.getSellingDishListByIds(new ArrayList<>(dishIds));
        if (sellingDishListByIds.size() != dishIds.size()) {
            List<Long> missingDishIds = new ArrayList<>();
            for (Long dishId : dishIds) {
                if (!sellingDishListByIds.contains(dishId)) {
                    missingDishIds.add(dishId);
                }
            }
            throw new BusinessException("菜品ID: " + missingDishIds + " 已停售或者不存在");
        }
    }
}

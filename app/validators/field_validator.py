"""Field-level validators and normalizers."""

from __future__ import annotations

import re
from datetime import datetime
from typing import Any

from app.models.schemas import FieldDefinition, ValidationResult


class BasicFieldValidator:
    """Validate normalized final values by field definition."""

    _phone_pattern = re.compile(r"^1\d{10}$")

    def normalize(self, field: FieldDefinition, value: Any) -> Any:
        if value is None:
            return value
        text = str(value).strip()
        if field.normalizer == "strip":
            return text
        if field.normalizer == "phone_cn":
            digits = "".join(ch for ch in text if ch.isdigit())
            return digits[-11:] if len(digits) >= 11 else digits
        return text

    def validate(self, field: FieldDefinition, value: Any) -> ValidationResult:
        text = "" if value is None else str(value).strip()

        if field.required and not text:
            return ValidationResult(valid=False, status="error", message="required field is empty")

        if not text:
            return ValidationResult(valid=True, status="warning", message="empty optional field")

        if field.data_type == "number":
            try:
                float(text)
            except ValueError:
                return ValidationResult(valid=False, status="error", message="invalid number format")

        if field.data_type == "date":
            if not self._is_date(text):
                return ValidationResult(valid=False, status="error", message="invalid date format")

        if field.validator == "phone_cn" and not self._phone_pattern.match(text):
            return ValidationResult(valid=False, status="error", message="invalid China phone number")

        return ValidationResult(valid=True, status="ok", message="ok")

    @staticmethod
    def _is_date(text: str) -> bool:
        for fmt in ["%Y-%m-%d", "%Y/%m/%d", "%Y.%m.%d"]:
            try:
                datetime.strptime(text, fmt)
                return True
            except ValueError:
                continue
        return False

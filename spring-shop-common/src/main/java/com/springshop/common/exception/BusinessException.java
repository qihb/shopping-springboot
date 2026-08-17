package com.springshop.common.exception;

import com.springshop.common.result.ResultCode;

/**
 * 业务异常
 */
public class BusinessException extends RuntimeException {

    private final Integer code;

    public BusinessException(String message) {
        this(ResultCode.SYSTEM_ERROR.getCode(), message);
    }

    public BusinessException(ResultCode resultCode) {
        this(resultCode.getCode(), resultCode.getMessage());
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public Integer getCode() {
        return code;
    }
}

package com.yizhaoqi.oaarchive.common;

/**
 * 业务异常：消息会原样展示给用户，所以要写成用户能看懂的中文。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        this(1, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}

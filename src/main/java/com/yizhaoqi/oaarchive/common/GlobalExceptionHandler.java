package com.yizhaoqi.oaarchive.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 把异常统一转换成 ApiResponse，前端只需要看 code 和 message。
 * 请求本身有问题（参数类型、缺参数、方法、Content-Type）返回 4xx 和中文提示；
 * 只有程序自己的错误才返回 500，并且不把异常原文返回给前端，详细信息写在日志里（技术难点 7.8）。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ApiResponse<Void> handleBiz(BizException e) {
        return ApiResponse.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + "：" + f.getDefaultMessage())
                .orElse("参数不正确");
        return ApiResponse.fail(400, msg);
    }

    /** 浏览器自动请求 favicon 等不存在的静态资源时返回 404，不记错误日志 */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNoResource(NoResourceFoundException e) {
        return ApiResponse.fail(404, "资源不存在");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleUnreadable(HttpMessageNotReadableException e) {
        return ApiResponse.fail(400, "请求内容格式不正确");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ApiResponse<Void> handleUploadSize(MaxUploadSizeExceededException e) {
        return ApiResponse.fail(413, "上传的文件太大");
    }

    /** 路径或查询参数类型不对，比如批次号写成字母、日期写成 2026-13-45、状态值不存在 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ApiResponse.fail(400, "参数“" + e.getName() + "”的值不正确：" + e.getValue());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return ApiResponse.fail(400, "缺少参数“" + e.getParameterName() + "”");
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleMissingPart(MissingServletRequestPartException e) {
        return ApiResponse.fail(400, "files".equals(e.getRequestPartName()) ? "请选择要上传的文件" : "缺少上传内容“" + e.getRequestPartName() + "”");
    }

    @ExceptionHandler(MultipartException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleMultipart(MultipartException e) {
        return ApiResponse.fail(400, "上传内容格式不正确，请重新上传");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public ApiResponse<Void> handleMethod(HttpRequestMethodNotSupportedException e) {
        return ApiResponse.fail(405, "接口不支持 " + e.getMethod() + " 请求");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public ApiResponse<Void> handleMediaType(HttpMediaTypeNotSupportedException e) {
        return ApiResponse.fail(415, "请求内容类型不支持，请使用 JSON");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleOther(Exception e) {
        log.error("未处理的异常", e);
        return ApiResponse.fail(500, "服务器内部错误，请稍后重试；详细信息见 data/logs 下的日志");
    }
}

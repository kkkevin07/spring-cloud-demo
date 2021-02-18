package com.pongsky.cloud.web.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pongsky.cloud.exception.DoesNotExistException;
import com.pongsky.cloud.exception.ExistException;
import com.pongsky.cloud.exception.FrequencyException;
import com.pongsky.cloud.exception.HttpException;
import com.pongsky.cloud.exception.InsertException;
import com.pongsky.cloud.exception.RemoteCallException;
import com.pongsky.cloud.exception.UpdateException;
import com.pongsky.cloud.exception.ValidationException;
import com.pongsky.cloud.model.annotation.Meaning;
import com.pongsky.cloud.response.GlobalResult;
import com.pongsky.cloud.response.enums.ResultCode;
import com.pongsky.cloud.utils.ip.IpUtils;
import com.pongsky.cloud.utils.jwt.dto.AuthInfo;
import com.pongsky.cloud.web.request.AuthUtils;
import com.pongsky.cloud.web.request.RequestUtils;
import feign.RetryableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import javax.servlet.http.HttpServletRequest;
import javax.validation.ConstraintViolationException;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Optional;

/**
 * 全局异常处理
 *
 * @author pengsenhao
 * @create 2021-02-11
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private final ObjectMapper jsonMapper;

    /**
     * 打印堆栈信息最小标识码
     */
    private static final int BOUNDARY = 500;

    /**
     * 校验 param 数据异常
     *
     * @param ex      ex
     * @param headers headers
     * @param status  status
     * @param request request
     * @return 校验 param 数据异常
     */
    @Override
    protected ResponseEntity<Object> handleBindException(BindException ex, HttpHeaders headers,
                                                         HttpStatus status, WebRequest request) {
        HttpServletRequest httpServletRequest = ((ServletRequestAttributes)
                (RequestContextHolder.currentRequestAttributes())).getRequest();
        Object result = getResult(ResultCode.BindException, getFieldMessages(ex.getBindingResult()),
                ex, httpServletRequest);
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    /**
     * 校验 param 数据异常
     *
     * @param ex      ex
     * @param headers headers
     * @param status  status
     * @param request request
     * @return 校验 param 数据异常
     */
    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(MissingServletRequestParameterException ex,
                                                                          HttpHeaders headers, HttpStatus status,
                                                                          WebRequest request) {
        HttpServletRequest httpServletRequest = ((ServletRequestAttributes)
                (RequestContextHolder.currentRequestAttributes())).getRequest();
        Object result = getResult(ResultCode.BindException, ex.getMessage(), ex, httpServletRequest);
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    /**
     * 校验 body 数据异常
     *
     * @param ex      ex
     * @param headers headers
     * @param status  status
     * @param request request
     * @return 校验 body 数据异常
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatus status,
                                                                  WebRequest request) {
        HttpServletRequest httpServletRequest = ((ServletRequestAttributes)
                (RequestContextHolder.currentRequestAttributes())).getRequest();
        Object result = getResult(ResultCode.MethodArgumentNotValidException,
                getFieldMessages(ex.getBindingResult()), ex, httpServletRequest);
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    /**
     * 获取字段错误信息
     *
     * @param bindingResult bindingResult
     * @return 字段错误信息
     */
    private String getFieldMessages(BindingResult bindingResult) {
        String escapeInterval = "\\.";
        String interval = ".";
        String listStart = "java.util.List<";
        StringBuilder stringBuilder = new StringBuilder("[ ");
        if (bindingResult.getTarget() == null) {
            bindingResult.getFieldErrors().forEach(error -> appendErrorMessage(stringBuilder,
                    error.getField(), error.getDefaultMessage()));
        } else {
            bindingResult.getFieldErrors().forEach(error -> {
                String filedName = error.getField();
                Field field = Arrays.stream(bindingResult.getTarget().getClass().getDeclaredFields())
                        .filter(f -> f.getName().equals(error.getField()))
                        .findFirst()
                        .orElse(null);
                if (field == null) {
                    appendErrorMessage(stringBuilder, filedName, error.getDefaultMessage());
                    return;
                }
                Meaning meaning = field.getAnnotation(Meaning.class);
                if (meaning != null) {
                    filedName = meaning.value();
                }
                if (!(filedName.split(escapeInterval).length > 1 && meaning != null)) {
                    appendErrorMessage(stringBuilder, filedName, error.getDefaultMessage());
                    return;
                }
                int i = filedName.lastIndexOf(interval, (filedName.lastIndexOf(interval) - 1)) + 1;
                String[] split = filedName.substring(i).split(escapeInterval);
                filedName = split[0].substring(0, filedName.lastIndexOf("["));
                String typeName = field.getGenericType().getTypeName();
                if (!(typeName.startsWith(listStart))) {
                    appendErrorMessage(stringBuilder, filedName, error.getDefaultMessage());
                    return;
                }
                typeName = typeName.substring(listStart.length(), typeName.lastIndexOf(">"));
                try {
                    Optional<Meaning> optionalMeaning = Arrays.stream(Class.forName(typeName).getDeclaredFields())
                            .filter(f -> f.getName().equals(split[1]))
                            .map(f -> f.getAnnotation(Meaning.class))
                            .findFirst();
                    if (optionalMeaning.isPresent()) {
                        filedName += optionalMeaning.get().value();
                    }
                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                }
                appendErrorMessage(stringBuilder, filedName, error.getDefaultMessage());
            });
        }
        return stringBuilder.append("]").toString();
    }

    /**
     * 追加错误字段信息
     *
     * @param stringBuilder 全部错误信息
     * @param filedName     字段名称
     * @param message       字段错误信息
     */
    private void appendErrorMessage(StringBuilder stringBuilder, String filedName, String message) {
        stringBuilder
                .append(filedName)
                .append(" ")
                .append(message)
                .append("; ");
    }

    /**
     * JSON 数据错误异常
     *
     * @param ex      ex
     * @param headers headers
     * @param status  status
     * @param request request
     * @return JSON 数据错误异常
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatus status,
                                                                  WebRequest request) {
        HttpServletRequest httpServletRequest = ((ServletRequestAttributes)
                (RequestContextHolder.currentRequestAttributes())).getRequest();
        Object result = getResult(ResultCode.HttpMessageNotReadableException, null, ex, httpServletRequest);
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    @Override
    protected ResponseEntity<Object> handleNoHandlerFoundException(NoHandlerFoundException ex, HttpHeaders headers,
                                                                   HttpStatus status, WebRequest request) {
        HttpServletRequest httpServletRequest = ((ServletRequestAttributes)
                (RequestContextHolder.currentRequestAttributes())).getRequest();
        Object result = getResult(ResultCode.NoHandlerFoundException,
                httpServletRequest.getRequestURI() + " 接口不存在", ex, httpServletRequest);
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                                         HttpHeaders headers, HttpStatus status,
                                                                         WebRequest request) {
        HttpServletRequest httpServletRequest = ((ServletRequestAttributes)
                (RequestContextHolder.currentRequestAttributes())).getRequest();
        Object result = getResult(ResultCode.HttpRequestMethodNotSupportedException,
                httpServletRequest.getMethod() + " 方法不存在", ex, httpServletRequest);
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    /**
     * 不存在异常
     *
     * @param exception exception
     * @param request   request
     * @return 不存在异常
     */
    @ExceptionHandler(value = DoesNotExistException.class)
    public Object doesNotExistException(DoesNotExistException exception, HttpServletRequest request) {
        return getResult(ResultCode.DoesNotExistException, exception.getLocalizedMessage(), exception, request);
    }

    /**
     * 校验异常
     *
     * @param exception exception
     * @param request   request
     * @return 校验异常
     */
    @ExceptionHandler(value = {ValidationException.class, ConstraintViolationException.class})
    public Object validationException(Exception exception, HttpServletRequest request) {
        return getResult(ResultCode.ValidationException, exception.getLocalizedMessage(), exception, request);
    }

    /**
     * HTTP 请求异常
     *
     * @param exception exception
     * @param request   request
     * @return HTTP 请求异常
     */
    @ExceptionHandler(value = HttpException.class)
    public Object httpException(HttpException exception, HttpServletRequest request) {
        return getResult(ResultCode.HttpException, exception.getLocalizedMessage(), exception, request);
    }

    /**
     * 访问权限异常
     *
     * @param exception exception
     * @param request   request
     * @return 访问权限异常
     */
    @ExceptionHandler(value = AccessDeniedException.class)
    public Object accessDeniedException(AccessDeniedException exception, HttpServletRequest request) {
        if (AuthUtils.getUser(request).equals(AuthInfo.PUBLIC_INFO)) {
            // 过期则重新登录
            return getResult(ResultCode.TokenExpiredException, "访问凭证已过期，请重新登录", exception, request);
        }
        return getResult(ResultCode.AccessDeniedException, null, exception, request);
    }

    /**
     * 空文件上传异常
     *
     * @param exception exception
     * @param request   request
     * @return 空文件上传异常
     */
    @ExceptionHandler(value = MultipartException.class)
    public Object multipartException(MultipartException exception, HttpServletRequest request) {
        return getResult(ResultCode.MultipartException, null, exception, request);
    }

    /**
     * 最大文件上传大小
     */
    @Value(value = "${spring.servlet.multipart.max-file-size}")
    private String maxFileSize;

    /**
     * 文件上传大小异常
     *
     * @param exception exception
     * @param request   request
     * @return 文件上传大小异常
     */
    @ExceptionHandler(value = MaxUploadSizeExceededException.class)
    public Object maxUploadSizeExceededException(MaxUploadSizeExceededException exception, HttpServletRequest request) {
        return getResult(ResultCode.MaxUploadSizeExceededException,
                "文件最大 " + maxFileSize + " ，请缩小文件内容后重新上传", exception, request);
    }

    /**
     * 非法参数异常
     *
     * @param exception exception
     * @param request   request
     * @return 非法参数异常
     */
    @ExceptionHandler(value = IllegalArgumentException.class)
    public Object illegalArgumentException(IllegalArgumentException exception, HttpServletRequest request) {
        return getResult(ResultCode.IllegalArgumentException, null, exception, request);
    }

    /**
     * 存在异常
     *
     * @param exception exception
     * @param request   request
     * @return 存在异常
     */
    @ExceptionHandler(value = ExistException.class)
    public Object existException(ExistException exception, HttpServletRequest request) {
        return getResult(ResultCode.ExistException, null, exception, request);
    }

    /**
     * 频率异常
     *
     * @param exception exception
     * @param request   request
     * @return 频率异常
     */
    @ExceptionHandler(value = FrequencyException.class)
    public Object frequencyException(FrequencyException exception, HttpServletRequest request) {
        return getResult(ResultCode.FrequencyException, null, exception, request);
    }

    /**
     * 保存异常
     *
     * @param exception exception
     * @param request   request
     * @return 保存异常
     */
    @ExceptionHandler(value = InsertException.class)
    public Object insertException(InsertException exception, HttpServletRequest request) {
        return getResult(ResultCode.InsertException, null, exception, request);
    }

    /**
     * 更新异常
     *
     * @param exception exception
     * @param request   request
     * @return 更新异常
     */
    @ExceptionHandler(value = UpdateException.class)
    public Object updateException(UpdateException exception, HttpServletRequest request) {
        return getResult(ResultCode.UpdateException, null, exception, request);
    }

    /**
     * 远程调用异常
     *
     * @param exception exception
     * @return 远程调用异常
     */
    @ExceptionHandler(value = RemoteCallException.class)
    public Object remoteCallException(RemoteCallException exception) {
        return exception.getResult();
    }

    /**
     * 远程调用异常
     *
     * @param exception exception
     * @param request   request
     * @return 远程调用异常
     */
    @ExceptionHandler(value = RetryableException.class)
    public Object retryableException(RetryableException exception, HttpServletRequest request) {
        return getResult(ResultCode.RemoteCallException, null, exception, request);
    }

    /**
     * 系统异常
     *
     * @param exception exception
     * @param request   request
     * @return 系统异常
     */
    @ExceptionHandler(value = Exception.class)
    public Object exception(Exception exception, HttpServletRequest request) {
        return getResult(ResultCode.Exception, null, exception, request);
    }

    /**
     * 封装异常响应体并打印
     *
     * @param resultCode resultCode
     * @param message    message
     * @param exception  exception
     * @param request    request
     * @return 封装异常响应体并打印
     */
    private Object getResult(ResultCode resultCode, String message, Exception exception, HttpServletRequest request) {
        String ip = IpUtils.getIp(request);
        // 可通过 getAttribute 获取自定义注解对 body 数据对特定业务场景进行特殊处理

        GlobalResult<Void> result = new GlobalResult<>(ip, resultCode, request.getRequestURI(), exception.getClass().getName());
        exception = getException(exception, 0);
        if (message != null) {
            result.setMessage(message);
        } else if (result.getMessage() == null) {
            if (exception.getLocalizedMessage() != null) {
                result.setMessage(exception.getLocalizedMessage());
            } else if (exception.getMessage() != null) {
                result.setMessage(exception.getMessage());
            }
        }
        log(exception, request, result);
        return result;
    }

    /**
     * 异常递归次数，防止堆溢出
     */
    private static final int THROWABLE_COUNT = 10;

    /**
     * 获取最底层的异常
     *
     * @param exception 异常
     * @param number    次数
     * @return 获取最底层的异常
     */
    private Exception getException(Exception exception, int number) {
        if (number > THROWABLE_COUNT) {
            return exception;
        }
        if (exception.getCause() != null) {
            return getException(exception, ++number);
        }
        return exception;
    }

    /**
     * 打印日志详细信息
     *
     * @param exception 异常
     * @param request   request
     * @param result    错误响应数据
     */
    private void log(Exception exception, HttpServletRequest request, GlobalResult<Void> result) {
        if (result.getCode() >= BOUNDARY) {
            log.error("");
            log.error("Exception Started");
            log.error("请求路径：{}", request.getRequestURI());
            log.error("方法类型：{}", request.getMethod());
            log.error("param 参数：{}", request.getQueryString());
            log.error("body 参数：{}", RequestUtils.getBody(request));
            log.error("异常详细信息：{}", result.getMessage());
            Arrays.asList(exception.getStackTrace()).forEach(stackTrace -> log.error(stackTrace.toString()));
        } else {
            log.info("");
            log.info("Exception Started");
            log.info("异常详细信息：{}", result.getMessage());
        }
        try {
            log.error("返回结果：{}", jsonMapper.writeValueAsString(result));
        } catch (JsonProcessingException e) {
            log.error(e.getLocalizedMessage());
        }
        log.error("Exception Ended");
    }

}

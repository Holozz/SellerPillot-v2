package com.sellerpilot.common;

public class ApiException extends RuntimeException {
    public final int status;
    public final Object detail;
    public ApiException(int status,String message) { this(status,(Object)message); }
    public ApiException(int status,Object detail) {
        super(String.valueOf(detail)); this.status=status; this.detail=detail;
    }
}

package com.gying.movie.client;

/** Fixed error codes are safe to persist; upstream URLs, responses and cookies never appear here. */
public final class BaiduActionException extends RuntimeException {
    private final String code;
    private final boolean retryable;
    private final boolean actionRequired;
    private final boolean uncertain;

    public BaiduActionException(String code, boolean retryable, boolean actionRequired, boolean uncertain) {
        super("Baidu operation " + safeCode(code));
        this.code = safeCode(code);
        this.retryable = retryable;
        this.actionRequired = actionRequired;
        this.uncertain = uncertain;
    }
    public String code() { return code; }
    public boolean retryable() { return retryable; }
    public boolean actionRequired() { return actionRequired; }
    public boolean uncertain() { return uncertain; }
    private static String safeCode(String value) {
        return value != null && value.matches("[A-Z][A-Z0-9_]{0,63}") ? value : "REJECTED";
    }
}

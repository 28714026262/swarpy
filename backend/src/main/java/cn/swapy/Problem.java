package cn.swapy;

public class Problem extends RuntimeException {
    public final String code;
    public Problem(String code, String message) { super(code + ": " + message); this.code = code; }
    public Problem(String code) { this(code, code); }
}

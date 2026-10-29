package dev.cordis.core;

/**
 * 框架统一异常:带一个稳定的错误码,方便程序按类型处理错误。
 */
public class CordisException extends RuntimeException {

    /** 稳定的错误码。 */
    public enum Code {
        /** 在已卸载或正在卸载的 fiber 上登记效果。 */
        INACTIVE_EFFECT("cannot create effect on inactive context"),
        /** 访问了声明过但尚未加载的服务。 */
        INACTIVE_ACCESS("cannot get required service in inactive context"),
        /** 访问了从未声明过的服务。 */
        UNDECLARED_ACCESS("cannot get property without inject"),
        /** 修改服务失败:从未提供过,或提供者不是当前 fiber。 */
        INVALID_SET("cannot set property without provide"),
        /** 同一隔离域内该服务名已被注册。 */
        DUPLICATE_PROVIDE("service has been registered"),
        /** 插件不合法或参数不对。 */
        INVALID_PLUGIN("invalid plugin"),
        /** 生命周期使用不当,例如重启已卸载的 fiber。 */
        INACTIVE_FIBER("fiber is disposed"),
        /** 隔离域调整失败。 */
        REALM_ERROR("isolation realm error");

        public final String message;

        Code(String message) {
            this.message = message;
        }
    }

    private final Code code;

    public CordisException(Code code) {
        super(code.message);
        this.code = code;
    }

    public CordisException(Code code, String message) {
        super(message == null ? code.message : message);
        this.code = code;
    }

    public CordisException(Code code, String message, Throwable cause) {
        super(message == null ? code.message : message, cause);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}

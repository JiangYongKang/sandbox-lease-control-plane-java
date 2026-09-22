package com.github.highcumontoa.sandboxleasecontrolplanejava.lease.provision;

/** 底层沙箱供应器异常。属于需要被归类的“底层异常”。 */
public class SandboxProvisionException extends RuntimeException {

    public SandboxProvisionException(String message) {
        super(message);
    }

    public SandboxProvisionException(String message, Throwable cause) {
        super(message, cause);
    }
}

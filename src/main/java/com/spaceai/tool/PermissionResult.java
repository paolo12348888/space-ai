package com.spaceai.tool;

/**
 * Strumentocontrollo permessiRisultato。
 * <p>
 * Corrisponde a space-ai valore di ritorno di Tool.checkPermissions() in
 */
public record PermissionResult(boolean allowed, String message) {

    /**  */
    public static final PermissionResult ALLOW = new PermissionResult(true, null);

    /** ， */
    public static PermissionResult deny(String reason) {
        return new PermissionResult(false, reason);
    }
}

package com.gpn.panel.state;

/**
 * Independent credentials for a single client (device). Each client has its own UUID and can be revoked / rotated individually.
 */
public class ClientAccount {

    public String id;
    public String name;
    public String uuid;
    /** Subscription token: no login required; used to fetch the client's own subscription */
    public String subToken;
    /** EAP username and password for iOS native IKEv2 */
    public String ikeUser;
    public String ikePass;
    public boolean enabled = true;
    public long createdAt = System.currentTimeMillis();
}

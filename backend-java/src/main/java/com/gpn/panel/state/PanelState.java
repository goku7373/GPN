package com.gpn.panel.state;

import java.util.ArrayList;
import java.util.List;

/**
 * All persisted state of the panel: Reality server keys + client credentials.
 * Stored purely as a JSON file, with no database dependency.
 */
public class PanelState {

    public int version = 1;

    public Reality reality = new Reality();

    public List<ClientAccount> clients = new ArrayList<>();

    public static class Reality {
        public String privateKey;
        public String publicKey;
        public String shortId;
    }
}

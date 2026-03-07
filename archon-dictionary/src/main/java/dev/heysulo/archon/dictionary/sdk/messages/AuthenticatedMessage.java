package dev.heysulo.archon.dictionary.sdk.messages;

import dev.heysulo.databridge.core.common.Message;

public abstract class AuthenticatedMessage implements Message {
    String authenticationToken = null;

    public String getAuthenticationToken() {
        return authenticationToken;
    }

    public void setAuthenticationToken(String authenticationToken) {
        this.authenticationToken = authenticationToken;
    }

    public boolean isAuthenticated() {
        return authenticationToken != null && !authenticationToken.isEmpty();
    }
}

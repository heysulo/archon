package dev.heysulo.archon.registry.applications;

import dev.heysulo.databridge.core.client.Client;
import dev.heysulo.databridge.core.client.callback.ClientCallback;
import dev.heysulo.databridge.core.common.Message;

public class Application implements ClientCallback {
    String group;
    String name;
    int rank;
    Client client;

    public Application(String group, String name, int rank) {
        this.name = name;
        this.rank = rank;
        this.group = group;
    }

    public String getDisplayName() {
        return String.format("%s:%s:%d", group, name, rank);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getRank() {
        return rank;
    }

    public void setRank(int rank) {
        this.rank = rank;
    }

    public Client getClient() {
        return client;
    }

    public void setClient(Client client) {
        client.setCallback(this);
        this.client = client;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public void send(Message message) {
        client.send(message);
    }

    @Override
    public void OnConnect(Client client) {

    }

    @Override
    public void OnDisconnect(Client client) {

    }

    @Override
    public void OnMessage(Client client, Message message) {

    }

    @Override
    public void OnError(Client client, Throwable throwable) {

    }
}

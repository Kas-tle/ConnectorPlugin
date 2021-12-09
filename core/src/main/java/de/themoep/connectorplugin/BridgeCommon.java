package de.themoep.connectorplugin;

/*
 * ConnectorPlugin
 * Copyright (C) 2021 Max Lee aka Phoenix616 (max@themoep.de)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public abstract class BridgeCommon<P extends ConnectorPlugin> {
    protected final P plugin;

    protected final Cache<Long, ResponseHandler<?>> responses = CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.MINUTES).build();
    protected final Cache<Long, Consumer<String>[]> consumers = CacheBuilder.newBuilder().expireAfterWrite(30, TimeUnit.MINUTES).build();

    protected static final Random RANDOM = new Random();

    public BridgeCommon(P plugin) {
        this.plugin = plugin;
    }

    protected void sendResponse(String target, long id, Object response, String... messages) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(target);
        out.writeLong(id);
        out.writeBoolean(true);
        if (response instanceof Boolean) {
            out.writeBoolean((Boolean) response);
        } else if (response instanceof String) {
            out.writeUTF((String) response);
        } else if (response instanceof LocationInfo) {
            ((LocationInfo) response).write(out);
        } else if (response == null) {
            out.writeUTF("");
        }
        sendResponseData(target, out.toByteArray());

        if (messages.length > 0) {
            sendResponseMessage(target, id, messages);
        }
    }

    protected void sendResponseMessage(String target, long id, String... messages) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(target);
        out.writeLong(id);
        out.writeBoolean(false);
        out.writeUTF(String.join("\n", messages));
        sendResponseData(target, out.toByteArray());
    }

    protected abstract void sendResponseData(String target, byte[] out);

    protected void handleResponse(long id, ByteArrayDataInput in) {
        ResponseHandler<?> responseHandler = responses.getIfPresent(id);
        if (responseHandler != null) {
            responses.invalidate(id);
            if (responseHandler instanceof ResponseHandler.Boolean) {
                ((ResponseHandler.Boolean) responseHandler).getFuture().complete(in.readBoolean());
            } else if (responseHandler instanceof ResponseHandler.String) {
                ((ResponseHandler.String) responseHandler).getFuture().complete(in.readUTF());
            } else if (responseHandler instanceof ResponseHandler.Location) {
                ((ResponseHandler.Location) responseHandler).getFuture().complete(LocationInfo.read(in));
            } else {
                plugin.logDebug("Response handler type " + responseHandler + " is not supported for ID " + id);
            }
        } else {
            plugin.logDebug("Could not find response for execution with ID " + id);
        }
    }

    public static class Action {
        public static final String STARTED = "started";
        public static final String SEND_TO_SERVER = "send_to_server";
        public static final String TELEPORT = "teleport";
        public static final String TELEPORT_TO_WORLD = "teleport_to_world";
        public static final String TELEPORT_TO_PLAYER = "teleport_to_player";
        public static final String GET_LOCATION = "get_location";
        public static final String GET_SERVER = "get_server";
        public static final String PLAYER_COMMAND = "player_command";
        public static final String CONSOLE_COMMAND = "console_command";
        public static final String RESPONSE = "response";
        public static final String REGISTER_COMMAND = "register_command";
        public static final String EXECUTE_COMMAND = "execute_command";
    }
}

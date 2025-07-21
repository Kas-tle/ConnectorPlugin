package de.themoep.connectorplugin.limbo;

/*
 * ConnectorPlugin
 * Copyright (C) 2025 Max Lee aka Phoenix616 (max@themoep.de)
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
import com.loohp.limbo.Limbo;
import com.loohp.limbo.commands.CommandExecutor;
import com.loohp.limbo.commands.CommandSender;
import com.loohp.limbo.commands.TabCompletor;
import com.loohp.limbo.events.EventHandler;
import com.loohp.limbo.events.Listener;
import com.loohp.limbo.events.player.PlayerJoinEvent;
import com.loohp.limbo.events.player.PlayerQuitEvent;
import com.loohp.limbo.events.player.PlayerSpawnEvent;
import com.loohp.limbo.location.Location;
import com.loohp.limbo.player.Player;
import com.loohp.limbo.world.World;
import de.themoep.connectorplugin.BridgeCommon;
import de.themoep.connectorplugin.LocationInfo;
import de.themoep.connectorplugin.ResponseHandler;
import de.themoep.connectorplugin.connector.MessageTarget;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static de.themoep.connectorplugin.connector.Connector.PLAYER_PREFIX;

public class Bridge extends BridgeCommon<LimboConnectorPlugin, Player> implements Listener {

    private final Cache<String, LoginRequest> loginRequests = CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.MINUTES).build();

    public Bridge(LimboConnectorPlugin plugin) {
        super(plugin);
        plugin.getLimbo().getEventsManager().registerEvents(plugin, this);

        registerHandler(Action.TELEPORT, (r, data) -> {
            ByteArrayDataInput in = ByteStreams.newDataInput(data);
            long id = in.readLong();
            String playerName = in.readUTF();
            LocationInfo location = LocationInfo.read(in);

            Player player = Limbo.getInstance().getPlayer(playerName);
            if (player != null) {
                player.teleport(new Location(
                        Limbo.getInstance().getWorld(location.getWorld()),
                        location.getX(),
                        location.getY(),
                        location.getZ(),
                        location.getYaw(),
                        location.getPitch()
                ));
                sendResponse(plugin.getServerName(), id, true);
            } else {
                sendResponse(plugin.getServerName(), id, false, "Player not found on this server!");
            }
        });

        registerMessageHandler(Action.TELEPORT_TO_WORLD, (receiver, message) -> {
            ByteArrayDataInput in = ByteStreams.newDataInput(message.getData());
            String senderServer = message.getReceivedMessage().getSendingServer();
            long id = in.readLong();
            String playerName = in.readUTF();
            String serverName = in.readUTF();
            if (!serverName.equals(plugin.getServerName())) {
                return;
            }

            String worldName = in.readUTF();

            World world = plugin.getServer().getWorld(worldName);
            if (world == null) {
                sendResponse(senderServer, id, false, "No world with the name " + worldName + " exists on the server!");
                plugin.logDebug("[M] Player " + playerName + " is online but no world with the name " + worldName + " to teleport to exists?");
                return;
            }

            markTeleporting(playerName);

            Player player = plugin.getServer().getPlayer(playerName);
            if (player != null) {
                plugin.logDebug("[M] Player " + playerName + " is online. Teleporting to spawn of world " + worldName);
                player.teleport(plugin.getLimbo().getServerProperties().getWorldSpawn());
                sendResponse(senderServer, id, true, "Player teleported to spawn of " + worldName + "!");
                unmarkTeleporting(playerName);
            } else {
                loginRequests.put(playerName.toLowerCase(Locale.ROOT), new LocationTeleportRequest(senderServer, id, adapt(plugin.getLimbo().getServerProperties().getWorldSpawn())));
                if (!plugin.getConnector().requiresPlayer() || !plugin.getServer().getPlayers().isEmpty()) {
                    sendToServer(playerName, serverName,
                            messages -> sendResponseMessage(senderServer, id, messages)
                    ).whenComplete((success, ex) -> {
                        sendResponse(senderServer, id, success, success ? "Player teleported to spawn of " + worldName + "!" : "Unable to teleport " + (ex != null ? ex.getMessage() : ""));
                    });
                }
            }
        });

        registerMessageHandler(Action.TELEPORT_TO_PLAYER, (receiver, message) -> {
            ByteArrayDataInput in = ByteStreams.newDataInput(message.getData());
            String senderServer = message.getReceivedMessage().getSendingServer();
            long id = in.readLong();
            String playerName = in.readUTF();
            String targetName = in.readUTF();

            markTeleporting(playerName);

            Player target = plugin.getServer().getPlayer(targetName);
            if (target != null) {
                Player player = plugin.getServer().getPlayer(playerName);
                if (player != null) {
                    plugin.logDebug("[M] Player " + playerName + " is online. Teleporting to player " + targetName);
                    player.teleport(target.getLocation());
                    sendResponse(senderServer, id, true, "Player teleported!");
                    unmarkTeleporting(playerName);
                } else {
                    loginRequests.put(playerName.toLowerCase(Locale.ROOT), new PlayerTeleportRequest(senderServer, id, targetName));
                    if (!plugin.getConnector().requiresPlayer() || !plugin.getServer().getPlayers().isEmpty()) {
                        sendToServer(playerName, plugin.getServerName(),
                                messages -> sendResponseMessage(senderServer, id, messages)
                        ).whenComplete((success, ex) -> {
                            sendResponse(senderServer, id, success, success ? "Player teleported!" : "Unable to teleport " + (ex != null ? ex.getMessage() : ""));
                        });
                    }
                }
            }
        });

        registerMessageHandler(Action.GET_LOCATION, (receiver, message) -> {
            ByteArrayDataInput in = ByteStreams.newDataInput(message.getData());
            String senderServer = message.getReceivedMessage().getSendingServer();
            long id = in.readLong();
            String playerName = in.readUTF();

            Player player = plugin.getServer().getPlayer(playerName);
            if (player != null) {
                sendResponse(senderServer, id, adapt(player.getLocation()));
            } else {
                sendResponse(senderServer, id, (LocationInfo) null);
            }
        });

        registerMessageHandler(Action.PLAYER_COMMAND, (receiver, message) -> {
            ByteArrayDataInput in = ByteStreams.newDataInput(message.getData());
            String senderServer = message.getReceivedMessage().getSendingServer();
            long id = in.readLong();
            String playerName = in.readUTF();
            UUID playerId = new UUID(in.readLong(), in.readLong());
            String command = in.readUTF();

            Player player = plugin.getLimbo().getPlayer(playerId);
            if (player == null) {
                player = plugin.getLimbo().getPlayer(playerName);
            }
            if (player == null) {
                plugin.logDebug("Could not find player " + playerName + "/" + playerId + " on this server to execute command " + command);
                sendResponse(senderServer, id, false, "Could not find player " + playerName + "/" + playerId + " on this server to execute command " + command);
                return;
            }

            plugin.logDebug("Command '" + command + "' for player '" + playerName + "' triggered from " + senderServer);
            plugin.getLimbo().dispatchCommand(player, command);

            sendResponse(senderServer, id, true);
        });

        registerMessageHandler(Action.CONSOLE_COMMAND, (receiver, message) -> {
            ByteArrayDataInput in = ByteStreams.newDataInput(message.getData());
            String senderServer = message.getReceivedMessage().getSendingServer();
            String targetServer = in.readUTF();
            if (targetServer.startsWith("p:")) {
                Player player = plugin.getLimbo().getPlayer(targetServer.substring(2));
                if (player == null) {
                    return;
                }
            }  else if (!targetServer.equals(plugin.getServerName())) {
                return;
            }
            long id = in.readLong();
            String command = in.readUTF();

            plugin.logDebug("Console command '" + command + "' triggered from " + senderServer);
            plugin.getLimbo().dispatchCommand(plugin.getLimbo().getConsole(), command);

            sendResponse(senderServer, id, true);
        });

        registerMessageHandler(Action.REGISTER_COMMAND, (receiver, message) -> {
            ByteArrayDataInput in = ByteStreams.newDataInput(message.getData());
            String senderServer = message.getReceivedMessage().getSendingServer();
            String pluginName = in.readUTF();
            String name = in.readUTF();

            String description = in.readUTF();
            String usage = in.readUTF();
            String permission = in.readUTF();
            String permissionMessage = in.readBoolean() ? in.readUTF() : null;
            int aliasCount = in.readInt();
            List<String> aliases = new ArrayList<>();
            for (int i = 0; i < aliasCount; i++) {
                aliases.add(in.readUTF());
            }
            plugin.getLimbo().getPluginManager().registerCommands(plugin, new BridgedCommandExecutor(senderServer, pluginName, name, description, usage, aliases, permission, permissionMessage));
        });

        registerHandler(Action.RESPONSE, (receiver, data) -> {
            ByteArrayDataInput in = ByteStreams.newDataInput(data);
            long id = in.readLong();
            boolean isCompletion = in.readBoolean();
            if (isCompletion) {
                handleResponse(id, in);
            } else {
                String message = in.readUTF();
                Consumer<String>[] consumer = consumers.getIfPresent(id);
                if (consumer != null) {
                    for (Consumer<String> stringConsumer : consumer) {
                        stringConsumer.accept(message);
                    }
                }
            }
        });
    }

    /**
     * Teleport a player to a certain server in the network
     * @param playerName    The name of the player to send
     * @param serverName    The name of the server to send to
     * @param consumer      Details about the sending
     * @return A future about whether the player could be sent
     */
    public CompletableFuture<Boolean> sendToServer(String playerName, String serverName, Consumer<String>... consumer) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        getServer(playerName).whenComplete((s, e) -> {
            // check player server existence
            if (s == null) {
                future.complete(false);
                for (Consumer<String> c : consumer) {
                    c.accept("Player " + playerName + " is not online!");
                }
                return;
            }
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            long id = RANDOM.nextLong();
            out.writeLong(id);
            out.writeUTF(playerName);
            out.writeUTF(serverName);
            responses.put(id, new ResponseHandler.Boolean(future));
            consumers.put(id, consumer);
            sendData(Action.SEND_TO_SERVER, MessageTarget.PROXY, PLAYER_PREFIX + playerName, out.toByteArray());
        });
        return future;
    }

    @Override
    @SafeVarargs
    public final CompletableFuture<Boolean> teleport(Player player, LocationInfo location, Consumer<String>... consumer) {
        return teleport(player.getName(), location, consumer);
    }

    @Override
    @SafeVarargs
    public final CompletableFuture<Boolean> teleport(String playerName, LocationInfo location, Consumer<String>... consumer) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        long id = RANDOM.nextLong();
        out.writeLong(id);
        out.writeUTF(playerName);
        location.write(out);
        responses.put(id, new ResponseHandler.Boolean(future));
        if (consumer != null && consumer.length > 0) {
            consumers.put(id, consumer);
        }
        sendData(Action.TELEPORT, MessageTarget.SERVER, location.getServer(), out.toByteArray());
        return future;
    }

    @Override
    @SafeVarargs
    public final CompletableFuture<Boolean> teleport(String playerName, String serverName, String worldName, Consumer<String>... consumer) {
        return teleport(playerName, new LocationInfo(serverName, worldName, 0, 0, 0), consumer);
    }

    @Override
    @SafeVarargs
    public final CompletableFuture<Boolean> teleport(Player player, String serverName, String worldName, Consumer<String>... consumer) {
        return teleport(player.getName(), serverName, worldName, consumer);
    }

    @Override
    @SafeVarargs
    public final CompletableFuture<Boolean> teleport(Player player, Player target, Consumer<String>... consumer) {
        return teleport(player.getName(), target.getName(), consumer);
    }

    @Override
    @SafeVarargs
    public final CompletableFuture<Boolean> teleport(String playerName, String targetName, Consumer<String>... consumer) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        long id = RANDOM.nextLong();
        out.writeLong(id);
        out.writeUTF(playerName);
        out.writeUTF(targetName);
        responses.put(id, new ResponseHandler.Boolean(future));
        if (consumer != null && consumer.length > 0) {
            consumers.put(id, consumer);
        }
        sendData(Action.TELEPORT_TO_PLAYER, MessageTarget.SERVER, PLAYER_PREFIX + targetName, out.toByteArray());
        return future;
    }

    @Override
    public CompletableFuture<LocationInfo> getLocation(Player player) {
        Location loc = player.getLocation();
        return CompletableFuture.completedFuture(new LocationInfo(plugin.getServerName(), loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch()));
    }

    @Override
    public CompletableFuture<String> getServer(Player player) {
        return CompletableFuture.completedFuture(plugin.getServerName());
    }

    @Override
    public void sendResponseData(String receiver, byte[] data) {
        plugin.getConnector().sendData(plugin, Action.RESPONSE, MessageTarget.SERVER, receiver, new BridgeMessage(data).writeToByteArray());
    }

    @EventHandler
    public void onPlayerSpawnEvent(PlayerSpawnEvent event) {
        LoginRequest request = loginRequests.getIfPresent(event.getPlayer().getName().toLowerCase(Locale.ROOT));
        if (request != null) {
            loginRequests.invalidate(event.getPlayer().getName().toLowerCase(Locale.ROOT));
            if (request instanceof LocationTeleportRequest) {
                event.setSpawnLocation(adapt(((LocationTeleportRequest) request).location));
                sendResponse(request.server, request.id, true, "Player login location changed");
                plugin.logDebug("Set spawn location of player " + event.getPlayer().getName() + " to " + ((LocationTeleportRequest) request).location);
            } else if (request instanceof PlayerTeleportRequest) {
                Player target = plugin.getServer().getPlayer(((PlayerTeleportRequest) request).targetName);
                if (target == null) {
                    event.setSpawnLocation(plugin.getLimbo().getServerProperties().getWorldSpawn());
                    sendResponse(request.server, request.id, false, "Target player " + ((PlayerTeleportRequest) request).targetName + " is no longer online?");
                    plugin.logDebug("Tried to set spawn location of player " + event.getPlayer().getName() + " to " + ((PlayerTeleportRequest) request).targetName + " but target wasn't online. Set to level spawn instead.");
                } else {
                    event.setSpawnLocation(target.getLocation());
                    sendResponse(request.server, request.id, true, "Player login location changed to " + target.getName() + "'s location");
                    plugin.logDebug("Set spawn location of player " + event.getPlayer().getName() + " to " + ((PlayerTeleportRequest) request).targetName + ". " + target.getLocation());
                }
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        unmarkTeleporting(event.getPlayer().getName());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        unmarkTeleporting(event.getPlayer().getName());
    }

    public Location adapt(LocationInfo location) {
        World world = plugin.getServer().getWorld(location.getWorld());
        if (world == null) {
            throw new IllegalArgumentException("No world with the name " + location.getWorld() + " exists!");
        }
        return new Location(
                world,
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch()
        );
    }

    public LocationInfo adapt(Location location) {
        if (location.getWorld() == null) {
            return null;
        }
        return new LocationInfo(
                plugin.getServerName(),
                location.getWorld().getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch()
        );
    }

    public CompletableFuture<Boolean> runProxyPlayerCommand(Player player, String command) {
        return runProxyPlayerCommand(player.getName(), command);
    }

    public CompletableFuture<Boolean> runProxyPlayerCommand(String playerName, String command) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        long id = RANDOM.nextLong();
        out.writeLong(id);
        out.writeUTF(playerName);
        out.writeUTF(command);
        responses.put(id, new ResponseHandler.Boolean(future));
        sendData(Action.PLAYER_COMMAND, MessageTarget.ALL_PROXIES, playerName, out.toByteArray());
        return future;
    }

    public CompletableFuture<Boolean> runProxyConsoleCommand(String command, Consumer<String>... consumer) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        long id = RANDOM.nextLong();
        out.writeLong(id);
        out.writeUTF(command);
        responses.put(id, new ResponseHandler.Boolean(future));
        if (consumer != null && consumer.length > 0) {
            consumers.put(id, consumer);
        }
        sendData(Action.CONSOLE_COMMAND, MessageTarget.ALL_PROXIES, out.toByteArray());
        return future;
    }

    private void sendCommandExecution(CommandSender sender, BridgedCommandExecutor executor, String label, String[] args) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(executor.getServer());
        out.writeUTF(sender instanceof Player ? sender.getName() : "");
        out.writeUTF(executor.getPluginName());
        out.writeUTF(executor.getName());
        out.writeUTF(label);
        out.writeInt(args.length);
        for (String arg : args) {
            out.writeUTF(arg);
        }

        if (sender instanceof Player) {
            adapt(((Player) sender).getLocation()).write(out);
            sendData(Action.EXECUTE_COMMAND, MessageTarget.PROXY, (Player) sender, out.toByteArray());
        } else {
            out.writeUTF(""); // Indicate empty location
            sendData(Action.EXECUTE_COMMAND, MessageTarget.ALL_PROXIES, out.toByteArray());
        }
    }

    private static class LoginRequest {
        private final String server;
        private final long id;

        private LoginRequest(String server, long id) {
            this.server = server;
            this.id = id;
        }
    }

    private static class LocationTeleportRequest extends LoginRequest {
        private final LocationInfo location;

        public LocationTeleportRequest(String server, long id, LocationInfo location) {
            super(server, id);
            this.location = location;
        }
    }

    private static class PlayerTeleportRequest extends LoginRequest {
        private final String targetName;

        public PlayerTeleportRequest(String server, long id, String targetName) {
            super(server, id);
            this.targetName = targetName;
        }
    }

    private class BridgedCommandExecutor implements CommandExecutor, TabCompletor {
        private final String server;
        private final String pluginName;
        private final String name;
        private final String permission;
        private final Collection<String> aliases;

        public BridgedCommandExecutor(String server, String pluginName, String name, String description, String usage, List<String> aliases, String permission, String permissionMessage) {
            this.server = server;
            this.pluginName = pluginName;
            this.name = name;
            this.permission = permission;
            this.aliases = aliases;
        }

        public String getServer() {
            return server;
        }

        public String getPluginName() {
            return pluginName;
        }

        public String getName() {
            return name;
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            if (!sender.hasPermission(this.permission)) {
                return;
            }

            if (args.length == 0) {
                return;
            }
            if (!args[0].equalsIgnoreCase(name) && !aliases.contains(args[0].toLowerCase(Locale.ROOT))) {
                return;
            }

            sendCommandExecution(sender, this, args[0], Arrays.copyOfRange(args, 2, args.length));
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String[] args) {
            if (!sender.hasPermission(this.permission)) {
                return new ArrayList<>();
            }

            List<String> completions = new ArrayList<>();
            if (args.length == 1) {
                String prefix = args[0].toLowerCase(Locale.ROOT);
                for (String alias : this.aliases) {
                    if (alias.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        completions.add(alias);
                    }
                }
            }
            return completions;
        }
    }
}

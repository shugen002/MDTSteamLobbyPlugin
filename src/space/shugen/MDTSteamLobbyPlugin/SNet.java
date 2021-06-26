package space.shugen.MDTSteamLobbyPlugin;

import arc.ApplicationListener;
import arc.Core;
import arc.Events;
import arc.func.Cons;
import arc.struct.IntMap;
import arc.util.Log;
import arc.util.pooling.Pools;
import com.codedisaster.steamworks.*;
import com.codedisaster.steamworks.SteamMatchmaking.ChatMemberStateChange;
import com.codedisaster.steamworks.SteamMatchmaking.LobbyType;
import com.codedisaster.steamworks.SteamNetworking.P2PSend;
import com.codedisaster.steamworks.SteamNetworking.P2PSessionError;
import com.codedisaster.steamworks.SteamNetworking.P2PSessionState;
import mindustry.core.Version;
import mindustry.game.EventType;
import mindustry.game.EventType.WaveEvent;
import mindustry.net.Administration;
import mindustry.net.ArcNetProvider.PacketSerializer;
import mindustry.net.Host;
import mindustry.net.Net.NetProvider;
import mindustry.net.NetConnection;
import mindustry.net.Packet;
import mindustry.net.Packets.Connect;
import mindustry.net.Packets.Disconnect;
import mindustry.net.Packets.StreamChunk;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;

import static mindustry.Vars.net;
import static mindustry.Vars.state;

public class SNet implements SteamNetworkingCallback, SteamMatchmakingCallback, SteamFriendsCallback, NetProvider {
    public final SteamNetworking snet = new SteamNetworking(this);
    public final SteamMatchmaking smat = new SteamMatchmaking(this);
    public final SteamFriends friends = new SteamFriends(this);

    final NetProvider provider;

    final PacketSerializer serializer = new PacketSerializer();
    final ByteBuffer writeBuffer = ByteBuffer.allocateDirect(16384);
    final ByteBuffer readBuffer = ByteBuffer.allocateDirect(16384);

    final CopyOnWriteArrayList<SteamConnection> connections = new CopyOnWriteArrayList<>();
    final IntMap<SteamConnection> steamConnections = new IntMap<>(); //maps steam ID -> valid net connection

    SteamID currentLobby, currentServer;
    Cons<Host> lobbyCallback;
    Runnable lobbyDoneCallback, joinCallback;

    public SNet(NetProvider provider) {

        this.provider = provider;

        Events.on(EventType.ServerLoadEvent.class, e -> Core.app.addListener(new ApplicationListener() {
            //read packets
            int length;
            SteamID from = new SteamID();

            @Override
            public void update() {
                while ((length = snet.isP2PPacketAvailable(0)) != 0) {
                    try {
                        readBuffer.position(0);
                        snet.readP2PPacket(from, readBuffer, 0);
                        int fromID = from.getAccountID();
                        Object output = serializer.read(readBuffer);

                        //it may be theoretically possible for this to be a framework message, if the packet is malicious or corrupted
                        if(!(output instanceof Packet)) return;

                        Packet pack = (Packet)output;

                        if (net.server()) {
                            SteamConnection con = steamConnections.get(fromID);
                            try {
                                //accept users on request
                                if (con == null) {
                                    con = new SteamConnection(from);
                                    Connect c = new Connect();
                                    c.addressTCP = "steam:" + from.getAccountID();

                                    Log.info("&bReceived STEAM connection: @", c.addressTCP);

                                    steamConnections.put(from.getAccountID(), con);
                                    connections.add(con);
                                    net.handleServerReceived(con, c);
                                }

                                net.handleServerReceived(con, pack);
                            } catch (Throwable e) {
                                Log.err(e);
                            }
                        } else if (currentServer != null && fromID == currentServer.getAccountID()) {
                            try {
                                net.handleClientReceived(pack);
                            } catch (Throwable t) {
                                net.handleException(t);
                            }
                        }
                    } catch (SteamException e) {
                        Log.err(e);
                    }
                }
            }
        }));

        Events.on(WaveEvent.class, e -> {
            if (currentLobby != null && net.server()) {
                smat.setLobbyData(currentLobby, "wave", state.wave + "");
            }
        });
    }

    public boolean isSteamClient() {
        return currentServer != null;
    }

    @Override
    public void connectClient(String ip, int port, Runnable success) throws IOException {
        if (ip.startsWith("steam:")) {
            String lobbyname = ip.substring("steam:".length());
            try {
                SteamID lobby = SteamID.createFromNativeHandle(Long.parseLong(lobbyname));
                joinCallback = success;
                smat.joinLobby(lobby);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid Steam ID: " + lobbyname);
            }
        } else {
            provider.connectClient(ip, port, success);
        }
    }

    @Override
    public void sendClient(Object object, boolean reliable){
        if(isSteamClient()){
            if(currentServer == null){
                Log.info("Not connected, quitting.");
                return;
            }

            try{
                writeBuffer.limit(writeBuffer.capacity());
                writeBuffer.position(0);
                serializer.write(writeBuffer, object);
                int length = writeBuffer.position();
                writeBuffer.flip();

                snet.sendP2PPacket(currentServer, writeBuffer, reliable || length >= 1200 ? P2PSend.Reliable : P2PSend.UnreliableNoDelay, 0);
            }catch(Exception e){
                net.showError(e);
            }
        }else{
            provider.sendClient(object, reliable);
        }
    }


    @Override
    public void disconnectClient() {
        if (isSteamClient()) {
            if (currentLobby != null) {
                smat.leaveLobby(currentLobby);
                snet.closeP2PSessionWithUser(currentServer);
                currentServer = null;
                currentLobby = null;
                net.handleClientReceived(new Disconnect());
            }
        } else {
            provider.disconnectClient();
        }
    }

    @Override
    public void discoverServers(Cons<Host> callback, Runnable done) {
        smat.addRequestLobbyListResultCountFilter(32);
        smat.requestLobbyList();
        lobbyCallback = callback;

        //after the steam lobby is done discovering, look for local network servers.
        lobbyDoneCallback = () -> provider.discoverServers(callback, done);
    }

    public void getLobbyList() {
        smat.addRequestLobbyListResultCountFilter(32);
        smat.addRequestLobbyListDistanceFilter(SteamMatchmaking.LobbyDistanceFilter.Worldwide);
        smat.requestLobbyList();
    }

    @Override
    public void pingHost(String address, int port, Cons<Host> valid, Cons<Exception> failed) {
        provider.pingHost(address, port, valid, failed);
    }

    @Override
    public void hostServer(int port) throws IOException {
        Log.info("hostServer");
        provider.hostServer(port);
        smat.createLobby(LobbyType.Public,
                Core.settings.getInt("playerlimit") == 0 ? 250 : Core.settings.getInt("playerlimit") + 1);

        Log.info("Server: @\nClient: @\nActive: @", net.server(), net.client(), net.active());
    }

    public void updateLobby() {
        Log.info("call updateLobby @ @", currentLobby, net.server());
        if (currentLobby != null && net.server()) {
            Log.info("call updateLobby 1");
            smat.setLobbyType(currentLobby, LobbyType.Public);
            smat.setLobbyMemberLimit(currentLobby,
                    Core.settings.getInt("playerlimit") == 0 ? 250 : Core.settings.getInt("playerlimit") + 1);
            smat.setLobbyData(currentLobby, "name", Administration.Config.name.string() +
                    (Administration.Config.desc.string().equalsIgnoreCase("off") ?
                            "" : ("\n" + Administration.Config.desc.string())));
            smat.setLobbyData(currentLobby, "mapname", state.map.name());
            smat.setLobbyData(currentLobby, "version", Version.build + "");
            smat.setLobbyData(currentLobby, "versionType", Version.type);
            smat.setLobbyData(currentLobby, "wave", state.wave + "");
            smat.setLobbyData(currentLobby, "gamemode", state.rules.mode().name() + "");
        }
    }

    @Override
    public void closeServer() {
        provider.closeServer();

        if (currentLobby != null) {
            smat.leaveLobby(currentLobby);
            for (SteamConnection con : steamConnections.values()) {
                con.close();
            }
            currentLobby = null;
        }

        steamConnections.clear();
    }

    @Override
    public Iterable<? extends NetConnection> getConnections() {
        //merge provider connections
        CopyOnWriteArrayList<NetConnection> connectionsOut = new CopyOnWriteArrayList<>(connections);
        for (NetConnection c : provider.getConnections()) connectionsOut.add(c);
        return connectionsOut;
    }

    void disconnectSteamUser(SteamID steamid) {
        //a client left
        int sid = steamid.getAccountID();
        snet.closeP2PSessionWithUser(steamid);

        if (steamConnections.containsKey(sid)) {
            SteamConnection con = steamConnections.get(sid);
            net.handleServerReceived(con, new Disconnect());
            steamConnections.remove(sid);
            connections.remove(con);
        }
    }


    @Override
    public void onFavoritesListChanged(int ip, int queryPort, int connPort, int appID, int flags, boolean add, int accountID) {
    }

    @Override
    public void onLobbyInvite(SteamID steamIDUser, SteamID steamIDLobby, long gameID) {
    }

    @Override
    public void onLobbyEnter(SteamID steamIDLobby, int chatPermissions, boolean blocked, SteamMatchmaking.ChatRoomEnterResponse response) {
    }

    @Override
    public void onLobbyDataUpdate(SteamID steamIDLobby, SteamID steamIDMember, boolean success) {
    }

    @Override
    public void onLobbyChatUpdate(SteamID lobby, SteamID who, SteamID changer, ChatMemberStateChange change) {
        Log.info("lobby @: @ caused @'s change: @", lobby.getAccountID(), who.getAccountID(), changer.getAccountID(), change);
        if (change == ChatMemberStateChange.Disconnected || change == ChatMemberStateChange.Left) {
            if (net.client()) {
                //host left, leave as well
                if (who.equals(currentServer) || who.equals(currentLobby)) {
                    net.disconnect();
                    Log.info("Current host left.");
                }
            } else {
                //a client left
                disconnectSteamUser(who);
            }
        }
    }

    @Override
    public void onLobbyChatMessage(SteamID steamIDLobby, SteamID steamIDUser, SteamMatchmaking.ChatEntryType entryType, int chatID) {
    }

    @Override
    public void onLobbyGameCreated(SteamID steamIDLobby, SteamID steamIDGameServer, int ip, short port) {
    }

    @Override
    public void onLobbyMatchList(int matches) {
        Log.info("found @ matches @", matches, lobbyDoneCallback);
        SteamID[] matchList = new SteamID[matches];
        for (int i = 0; i < matches; i++) {
            matchList[i] = smat.getLobbyByIndex(i);
        }
        Log.info(String.join(",", (String[]) Arrays.stream(matchList).map(SteamNativeHandle::toString).toArray()));
    }

    @Override
    public void onLobbyKicked(SteamID steamID, SteamID steamID1, boolean b) {
        Log.info("Kicked: @ @ @", steamID, steamID1, b);
    }

    @Override
    public void onLobbyCreated(SteamResult result, SteamID steamID) {
        Log.info("onLobbyCreated", result, steamID);
        if (!net.server()) {
            Log.info("Lobby created on server: @, ignoring.", steamID);
            return;
        }

        Log.info("Lobby @ created? @", result, steamID.getAccountID());
        if (result == SteamResult.OK) {
            currentLobby = steamID;

            smat.setLobbyData(currentLobby, "name", Administration.Config.name.string() +
                    (Administration.Config.desc.string().equalsIgnoreCase("off") ?
                            "" : ("\n" + Administration.Config.desc.string())));
            smat.setLobbyData(steamID, "mapname", state.map.name());
            smat.setLobbyData(steamID, "version", Version.build + "");
            smat.setLobbyData(steamID, "versionType", "official");
            smat.setLobbyData(steamID, "wave", state.wave + "");
            smat.setLobbyData(steamID, "gamemode", state.rules.mode().name() + "");
        }

    }

    @Override
    public void onFavoritesListAccountsUpdated(SteamResult result) {

    }


    @Override
    public void onP2PSessionConnectFail(SteamID steamIDRemote, P2PSessionError sessionError) {
        if (net.server()) {
            Log.info("@ has disconnected: @", steamIDRemote.getAccountID(), sessionError);
            disconnectSteamUser(steamIDRemote);
        } else if (steamIDRemote.equals(currentServer)) {
            Log.info("Disconnected! @: @", steamIDRemote.getAccountID(), sessionError);
            net.handleClientReceived(new Disconnect());
        }
    }

    @Override
    public void onP2PSessionRequest(SteamID steamIDRemote) {
        Log.info("Connection request: @", steamIDRemote.getAccountID());
        if (net.server()) {
            Log.info("Am server, accepting request from @ @", steamIDRemote.getAccountID(), snet.acceptP2PSessionWithUser(steamIDRemote));

        }
    }


    public void setLobbyid(String str) {
        currentLobby = SteamID.createFromNativeHandle(Long.parseUnsignedLong(str, 16));
    }

    @Override
    public void onSetPersonaNameResponse(boolean success, boolean localSuccess, SteamResult result) {

    }

    @Override
    public void onPersonaStateChange(SteamID steamID, SteamFriends.PersonaChange change) {

    }

    @Override
    public void onGameOverlayActivated(boolean active) {

    }

    @Override
    public void onGameLobbyJoinRequested(SteamID steamIDLobby, SteamID steamIDFriend) {

    }

    @Override
    public void onAvatarImageLoaded(SteamID steamID, int image, int width, int height) {

    }

    @Override
    public void onFriendRichPresenceUpdate(SteamID steamIDFriend, int appID) {

    }

    @Override
    public void onGameRichPresenceJoinRequested(SteamID steamIDFriend, String connect) {

    }

    @Override
    public void onGameServerChangeRequested(String server, String password) {

    }

    public class SteamConnection extends NetConnection {
        final SteamID sid;
        final P2PSessionState state = new P2PSessionState();

        public SteamConnection(SteamID sid) {
            super(sid.getAccountID() + "");
            this.sid = sid;
            Log.info("Create STEAM client @", sid.getAccountID());
        }

        @Override
        public void send(Object object, boolean reliable) {
            try {
                writeBuffer.limit(writeBuffer.capacity());
                writeBuffer.position(0);
                serializer.write(writeBuffer, object);
                int length = writeBuffer.position();
                writeBuffer.flip();

                snet.sendP2PPacket(sid, writeBuffer,
                        reliable  || length >= 1200 ? object instanceof StreamChunk ? P2PSend.ReliableWithBuffering : P2PSend.Reliable : P2PSend.UnreliableNoDelay,
                        0);
            } catch (Exception e) {
                Log.err(e);
                Log.info("Error sending packet. Disconnecting invalid client!");
                close();

                SteamConnection k = steamConnections.get(sid.getAccountID());
                if (k != null) steamConnections.remove(sid.getAccountID());
            }
        }

        @Override
        public boolean isConnected() {
            snet.getP2PSessionState(sid, state);
            return true;//state.isConnectionActive();
        }

        @Override
        public void close() {
            disconnectSteamUser(sid);
        }
    }
}

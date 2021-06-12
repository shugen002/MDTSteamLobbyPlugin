package space.shugen.MDTSteamLobbyPlugin;

import arc.ApplicationListener;
import arc.Core;
import arc.Events;
import arc.util.CommandHandler;
import arc.util.Log;
import arc.util.Strings;
import com.codedisaster.steamworks.SteamAPI;
import mindustry.Vars;
import mindustry.core.Platform;
import mindustry.game.EventType;
import mindustry.gen.Groups;
import mindustry.mod.Plugin;
import mindustry.net.ArcNetProvider;
import mindustry.net.Net;

import java.lang.reflect.Field;

import static mindustry.Vars.*;

public class MDTSteamLobbyPlugin extends Plugin {

    //called when game initializes
    @Override
    public void init() {
        try {
            SteamAPI.loadLibraries();

            if (!SteamAPI.init()) {
                Log.err("Steam client not running.");
            } else {
                initSteam();
                Vars.steam = true;
                Log.err("Steam client running.");
            }
            if (SteamAPI.restartAppIfNecessary(SVars.steamID)) {
                System.exit(0);
            }
        } catch (NullPointerException ignored) {
            ignored.printStackTrace();
            Log.err(ignored.getMessage());
            steam = false;
            Log.info("Running in offline mode.");
        } catch (Throwable e) {
            steam = false;
            Log.err("Failed to load Steam native libraries.");
            Log.err(e);
        }
        Vars.platform = new Platform() {
            @Override
            public Net.NetProvider getNet() {
                return steam ? SVars.net : new ArcNetProvider();
            }

            @Override
            public void updateLobby() {
                if (SVars.net != null) {
                    SVars.net.updateLobby();
                }
            }

            @Override
            public void updateRPC() {
                //if we're using neither discord nor steam, do no work
                if (!steam) return;

                //common elements they each share
                boolean inGame = state.isGame();
                String gameMapWithWave = "Unknown Map";
                String gameMode = "";
                String gamePlayersSuffix = "";
                String uiState = "";

                if (inGame) {
                    //TODO implement nice name for sector
                    gameMapWithWave = Strings.capitalize(Strings.stripColors(state.map.name()));

                    if (state.rules.waves) {
                        gameMapWithWave += " | Wave " + state.wave;
                    }
                    gameMode = state.rules.pvp ? "PvP" : state.rules.attackMode ? "Attack" : "Survival";
                    if (net.active() && Groups.player.size() > 1) {
                        gamePlayersSuffix = " | " + Groups.player.size() + " Players";
                    }
                } else {
                    if (ui.editor != null && ui.editor.isShown()) {
                        uiState = "In Editor";
                    } else if (ui.planet != null && ui.planet.isShown()) {
                        uiState = "In Launch Selection";
                    } else {
                        uiState = "In Menu";
                    }
                }

                //Steam mostly just expects us to give it a nice string, but it apparently expects "steam_display" to always be a loc token, so I've uploaded this one which just passes through 'steam_status' raw.
                SVars.net.friends.setRichPresence("steam_display", "#steam_status_raw");

                if (inGame) {
                    SVars.net.friends.setRichPresence("steam_status", gameMapWithWave);
                } else {
                    SVars.net.friends.setRichPresence("steam_status", uiState);
                }

            }
        };

        Core.app.addListener(new ApplicationListener() {
            @Override
            public void update() {
                if (SteamAPI.isSteamRunning()) {
                    SteamAPI.runCallbacks();
                }
            }
        });

    }

    void initSteam() throws NoSuchFieldException, IllegalAccessException {
        Field providerField = net.getClass().getDeclaredField("provider");
        providerField.setAccessible(true);
        Net.NetProvider provider = (Net.NetProvider) providerField.get(net);
        SVars.net = new SNet(provider);
        providerField.set(net, SVars.net);
        SVars.user = new SUser();
        boolean[] isShutdown = {false};

        Events.on(EventType.WorldLoadEvent.class, (e) -> {
            SVars.net.updateLobby();
        });

        Events.on(EventType.DisposeEvent.class, event -> {
            SteamAPI.shutdown();
            isShutdown[0] = true;
        });

        //steam shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!isShutdown[0]) {
                SteamAPI.shutdown();
            }
        }));
    }

    /**
     * Register any commands to be used on the server side, e.g. from the console.
     *
     * @param handler
     */
    @Override
    public void registerServerCommands(CommandHandler handler) {
        handler.register("test", "test", (e) -> {
            Log.info("1");
        });
    }
}

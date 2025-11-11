package com.hfm.app;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.libtorrent4j.AddTorrentParams;
import org.libtorrent4j.AlertListener;
import org.libtorrent4j.InfoHash;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.Vectors;
import org.libtorrent4j.alerts.Alert;
import org.libtorrent4j.alerts.AlertType;
import org.libtorrent4j.alerts.StateUpdateAlert;
import org.libtorrent4j.alerts.TorrentErrorAlert;
import org.libtorrent4j.alerts.TorrentFinishedAlert;
import org.libtorrent4j.swig.create_torrent;
import org.libtorrent4j.swig.file_storage;
import org.libtorrent4j.swig.libtorrent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TorrentManager with reflection-based fallbacks to handle micro-version API differences
 * across libtorrent4j releases. All original logic preserved.
 */
public class TorrentManager {

    private static final String TAG = "TorrentManager";
    private static volatile TorrentManager instance;

    private final SessionManager sessionManager;
    private final Context appContext;

    // Maps to track active torrents
    private final Map<String, TorrentHandle> activeTorrents; // dropRequestId -> TorrentHandle
    // Use hex string keys for info-hash to avoid class mismatch between Sha1Hash/InfoHash across versions.
    private final Map<String, String> hashToIdMap; // infoHashHex -> dropRequestId

    private TorrentManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.sessionManager = new SessionManager();
        this.activeTorrents = new ConcurrentHashMap<>();
        this.hashToIdMap = new ConcurrentHashMap<>();

        // Set up the listener for torrent events
        sessionManager.addListener(new AlertListener() {
            @Override
            public int[] types() {
                // Return null to listen to all alerts so we don't depend on numeric codes.
                return null;
            }

            @Override
            public void alert(Alert<?> alert) {
                // Newer libtorrent4j: alert.type() returns AlertType enum.
                // Older variants may return int. Handle both.
                try {
                    Object t = alert.type();
                    // If AlertType enum:
                    if (t instanceof AlertType) {
                        AlertType at = (AlertType) t;
                        if (at == AlertType.STATE_UPDATE) {
                            handleStateUpdate((StateUpdateAlert) alert);
                        } else if (at == AlertType.TORRENT_FINISHED) {
                            handleTorrentFinished((TorrentFinishedAlert) alert);
                        } else if (at == AlertType.TORRENT_ERROR) {
                            handleTorrentError((TorrentErrorAlert) alert);
                        }
                    } else if (t instanceof Integer) {
                        int code = (Integer) t;
                        // numeric codes historically: state_update_alert ~7, torrent_finished_alert ~15, torrent_error_alert ~13
                        if (code == 7) {
                            handleStateUpdate((StateUpdateAlert) alert);
                        } else if (code == 15) {
                            handleTorrentFinished((TorrentFinishedAlert) alert);
                        } else if (code == 13) {
                            handleTorrentError((TorrentErrorAlert) alert);
                        }
                    } else {
                        // Unknown type; try instance checks by class-name
                        String tn = t == null ? "" : t.getClass().getSimpleName().toLowerCase();
                        if (tn.contains("state")) handleStateUpdate((StateUpdateAlert) alert);
                        else if (tn.contains("finished")) handleTorrentFinished((TorrentFinishedAlert) alert);
                        else if (tn.contains("error")) handleTorrentError((TorrentErrorAlert) alert);
                    }
                } catch (Throwable t) {
                    // Fallback: try instanceof alerts
                    try {
                        if (alert instanceof StateUpdateAlert) handleStateUpdate((StateUpdateAlert) alert);
                        else if (alert instanceof TorrentFinishedAlert) handleTorrentFinished((TorrentFinishedAlert) alert);
                        else if (alert instanceof TorrentErrorAlert) handleTorrentError((TorrentErrorAlert) alert);
                    } catch (Throwable ignored) {
                        Log.w(TAG, "Unknown alert type received: " + t.getMessage());
                    }
                }
            }
        });

        // Start the session
        sessionManager.start();
    }

    public static TorrentManager getInstance(Context context) {
        if (instance == null) {
            synchronized (TorrentManager.class) {
                if (instance == null) {
                    instance = new TorrentManager(context);
                }
            }
        }
        return instance;
    }

    private void handleStateUpdate(StateUpdateAlert alert) {
        List<TorrentStatus> statuses = alert.status();
        for (TorrentStatus status : statuses) {
            String infoHex = extractInfoHashHexFromStatus(status);
            if (infoHex == null) continue;

            String dropRequestId = hashToIdMap.get(infoHex);
            if (dropRequestId != null) {
                Intent intent = new Intent(DropProgressActivity.ACTION_UPDATE_STATUS);
                intent.putExtra(DropProgressActivity.EXTRA_STATUS_MAJOR, status.isSeeding() ? "Sending File..." : "Receiving File...");
                intent.putExtra(DropProgressActivity.EXTRA_STATUS_MINOR, "Peers: " + status.numPeers() + " | Down: " + (status.downloadPayloadRate() / 1024) + " KB/s | Up: " + (status.uploadPayloadRate() / 1024) + " KB/s");

                long totalDone = safeLong(status, "totalDone");
                long totalWanted = safeLong(status, "totalWanted");
                intent.putExtra(DropProgressActivity.EXTRA_PROGRESS, (int) Math.min(totalDone, Integer.MAX_VALUE));
                intent.putExtra(DropProgressActivity.EXTRA_MAX_PROGRESS, (int) Math.min(totalWanted, Integer.MAX_VALUE));
                intent.putExtra(DropProgressActivity.EXTRA_BYTES_TRANSFERRED, totalDone);

                LocalBroadcastManager.getInstance(appContext).sendBroadcast(intent);
            }
        }
    }

    private void handleTorrentFinished(TorrentFinishedAlert alert) {
        TorrentHandle handle = alert.handle();
        String infoHex = extractInfoHashHexFromHandle(handle);
        String dropRequestId = infoHex == null ? null : hashToIdMap.get(infoHex);
        Log.d(TAG, "Torrent finished for request ID: " + dropRequestId);

        if (dropRequestId != null) {
            Intent intent = new Intent(DropProgressActivity.ACTION_TRANSFER_COMPLETE);
            LocalBroadcastManager.getInstance(appContext).sendBroadcast(intent);
        }

        // Cleanup
        cleanupTorrent(handle);
    }

    private void handleTorrentError(TorrentErrorAlert alert) {
        TorrentHandle handle = alert.handle();
        String infoHex = extractInfoHashHexFromHandle(handle);
        String dropRequestId = infoHex == null ? null : hashToIdMap.get(infoHex);

        String errorMsg;
        try {
            errorMsg = alert.message();
        } catch (Throwable t) {
            // Some bindings use alert.error().message()
            try {
                Object err = callMethodSafely(alert, "error");
                if (err != null) {
                    errorMsg = (String) callMethodSafely(err, "message");
                } else errorMsg = "Unknown torrent error";
            } catch (Throwable tt) {
                errorMsg = "Unknown torrent error";
            }
        }

        Log.e(TAG, "Torrent error for request ID " + dropRequestId + ": " + errorMsg);

        if (dropRequestId != null) {
            Intent errorIntent = new Intent(DownloadService.ACTION_DOWNLOAD_ERROR);
            errorIntent.putExtra(DownloadService.EXTRA_ERROR_MESSAGE, "Torrent transfer failed: " + errorMsg);
            LocalBroadcastManager.getInstance(appContext).sendBroadcast(errorIntent);

            LocalBroadcastManager.getInstance(appContext).sendBroadcast(new Intent(DropProgressActivity.ACTION_TRANSFER_ERROR));
        }

        // Cleanup
        cleanupTorrent(handle);
    }

    public String startSeeding(File dataFile, String dropRequestId) {
        if (dataFile == null || !dataFile.exists()) {
            Log.e(TAG, "Data file to be seeded does not exist.");
            return null;
        }

        File torrentFile = null;
        try {
            torrentFile = createTorrentFile(dataFile);
            final TorrentInfo torrentInfo = new TorrentInfo(torrentFile);

            // We will attempt several ways to add the torrent:
            // 1) sessionManager.download(TorrentInfo, File) that returns TorrentHandle
            // 2) sessionManager.download(TorrentInfo, File) that returns void (older binding) -> try to retrieve handle from session/status
            // 3) sessionManager.addTorrent(AddTorrentParams) or similar via reflection
            TorrentHandle handle = tryDownloadViaReflection(torrentInfo, dataFile.getParentFile());
            if (handle == null) {
                // fallback: attempt AddTorrentParams and reflective call
                AddTorrentParams params = new AddTorrentParams();
                try {
                    // Many bindings: params.setTorrentInfo / setTi
                    callMethodIfExists(params, "setTorrentInfo", new Class[]{TorrentInfo.class}, new Object[]{torrentInfo});
                } catch (Throwable ignored) {
                }
                try {
                    callMethodIfExists(params, "setSavePath", new Class[]{String.class}, new Object[]{dataFile.getParentFile().getAbsolutePath()});
                } catch (Throwable ignored) {
                }

                handle = tryAddTorrentParams(params, dataFile.getParentFile());
            }

            if (handle != null && handle.isValid()) {
                activeTorrents.put(dropRequestId, handle);
                String infoHex = extractInfoHashHexFromHandle(handle);
                if (infoHex != null) hashToIdMap.put(infoHex, dropRequestId);
                String magnetLink;
                try {
                    magnetLink = handle.makeMagnetUri();
                } catch (Throwable t) {
                    magnetLink = "magnet:?xt=urn:btih:" + (extractInfoHashHexFromHandle(handle) != null ? extractInfoHashHexFromHandle(handle) : "");
                }
                Log.d(TAG, "Started seeding for request ID " + dropRequestId + ". Magnet: " + magnetLink);
                return magnetLink;
            } else {
                Log.e(TAG, "Failed to get valid TorrentHandle after adding seed.");
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to create torrent for seeding: " + e.getMessage(), e);
            return null;
        } finally {
            if (torrentFile != null && torrentFile.exists()) {
                torrentFile.delete();
            }
        }
    }

    private File createTorrentFile(File dataFile) throws IOException {
        file_storage fs = new file_storage();
        // try common add_files forms
        boolean added = false;
        try {
            // libtorrent.add_files(fs, path)
            callStaticMethodIfExists(libtorrent.class, "add_files", new Class[]{file_storage.class, String.class}, new Object[]{fs, dataFile.getAbsolutePath()});
            added = true;
        } catch (Throwable ignored) {
        }
        if (!added) {
            // fallback attempt: libtorrent.add_files_ex or other names
            try {
                callStaticMethodIfExists(libtorrent.class, "add_files_ex", new Class[]{file_storage.class, String.class}, new Object[]{fs, dataFile.getAbsolutePath()});
                added = true;
            } catch (Throwable ignored) {
            }
        }
        if (!added) {
            // If the binding does not provide add_files, we cannot proceed automatically — throw with guide message.
            throw new IOException("libtorrent.add_files(...) not available in this libtorrent4j binding. Build the .torrent offline or use a libtorrent binding that provides add_files.");
        }

        // piece size helpers
        int pieceSize = -1;
        try {
            Object val = callStaticMethodIfExists(libtorrent.class, "optimal_piece_size", new Class[]{file_storage.class}, new Object[]{fs});
            if (val instanceof Number) pieceSize = ((Number) val).intValue();
        } catch (Throwable ignored) {
        }
        if (pieceSize <= 0) {
            try {
                Object val = callStaticMethodIfExists(libtorrent.class, "piece_size", new Class[]{file_storage.class}, new Object[]{fs});
                if (val instanceof Number) pieceSize = ((Number) val).intValue();
            } catch (Throwable ignored) {
            }
        }
        if (pieceSize <= 0) pieceSize = 16 * 1024; // fallback

        // create_torrent constructor: try common signatures via reflection
        create_torrent ct = null;
        try {
            // try (file_storage, int)
            Constructor<?> cons = findConstructor(create_torrent.class, new Class[]{file_storage.class, int.class});
            if (cons != null) {
                ct = (create_torrent) cons.newInstance(fs, pieceSize);
            }
        } catch (Throwable ignored) {
        }
        if (ct == null) {
            // try alternative constructor signatures that exist in some bindings
            try {
                Constructor<?> cons = findConstructor(create_torrent.class, new Class[]{long.class, boolean.class});
                if (cons != null) ct = (create_torrent) cons.newInstance(0L, false);
            } catch (Throwable ignored) {
            }
        }
        if (ct == null) {
            throw new IOException("create_torrent constructor not available for (file_storage,int) in this binding. Please create a .torrent offline or use a matching libtorrent4j build.");
        }

        // generate and bencode -> convert to byte[]
        byte[] torrentBytes;
        try {
            Object gen = callMethodIfExists(ct, "generate");
            Object bencoded = callMethodIfExists(gen, "bencode");
            // bencoded often returns a SWIG byte_vector; convert with Vectors helper if present
            try {
                // Try Vectors.byte_vector2bytes
                torrentBytes = Vectors.byte_vector2bytes(bencoded);
            } catch (Throwable t) {
                // Fallback: if bencoded is byte[] already
                if (bencoded instanceof byte[]) {
                    torrentBytes = (byte[]) bencoded;
                } else {
                    // Try reflection to access elements
                    torrentBytes = attemptByteVectorToBytesReflective(bencoded);
                    if (torrentBytes == null) throw new IOException("Unable to convert bencoded byte vector to byte[]");
                }
            }
        } catch (Throwable t) {
            throw new IOException("Failed to bencode generated torrent: " + t.getMessage(), t);
        }

        File tempTorrent = File.createTempFile("seed_", ".torrent", dataFile.getParentFile());
        try (FileOutputStream fos = new FileOutputStream(tempTorrent)) {
            fos.write(torrentBytes);
            fos.flush();
        }
        return tempTorrent;
    }

    public void startDownload(String magnetLink, File saveDirectory, String dropRequestId) {
        if (!saveDirectory.exists()) saveDirectory.mkdirs();

        try {
            // Preferred: sessionManager.fetchMagnet(magnetLink, timeout, tempDir) if available
            byte[] torrentData = null;
            try {
                Object fetched = callMethodIfExists(sessionManager, "fetchMagnet", new Class[]{String.class, int.class, File.class}, new Object[]{magnetLink, 30, saveDirectory});
                if (fetched instanceof byte[]) torrentData = (byte[]) fetched;
            } catch (Throwable ignored) {
            }

            if (torrentData != null) {
                TorrentInfo ti = TorrentInfo.bdecode(torrentData);
                TorrentHandle handle = tryDownloadViaReflection(ti, saveDirectory);
                if (handle != null && handle.isValid()) {
                    activeTorrents.put(dropRequestId, handle);
                    String infoHex = extractInfoHashHexFromHandle(handle);
                    if (infoHex != null) hashToIdMap.put(infoHex, dropRequestId);
                    Log.d(TAG, "Started download for request ID: " + dropRequestId);
                    return;
                } else {
                    Log.e(TAG, "Failed to obtain valid handle after fetchMagnet route.");
                }
            }

            // Fallback: try AddTorrentParams.parseMagnetUri and different add/download methods
            AddTorrentParams params = null;
            try {
                params = (AddTorrentParams) callStaticMethodIfExists(AddTorrentParams.class, "parseMagnetUri", new Class[]{String.class}, new Object[]{magnetLink});
            } catch (Throwable ignored) {}

            if (params != null) {
                // attempt to download using params via reflection
                TorrentHandle handle = tryAddTorrentParams(params, saveDirectory);
                if (handle != null && handle.isValid()) {
                    activeTorrents.put(dropRequestId, handle);
                    String infoHex = extractInfoHashHexFromHandle(handle);
                    if (infoHex != null) hashToIdMap.put(infoHex, dropRequestId);
                    Log.d(TAG, "Started download for request ID: " + dropRequestId);
                    return;
                } else {
                    Log.e(TAG, "Failed to obtain valid handle from AddTorrentParams path.");
                }
            }

            Log.e(TAG, "Failed to start download: no metadata obtained from magnet link.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start download: " + e.getMessage(), e);
        }
    }

    private void cleanupTorrent(TorrentHandle handle) {
        if (handle == null || !handle.isValid()) return;

        String infoHex = extractInfoHashHexFromHandle(handle);
        String dropRequestId = infoHex == null ? null : hashToIdMap.get(infoHex);

        if (dropRequestId != null) {
            activeTorrents.remove(dropRequestId);
            hashToIdMap.remove(infoHex);
        }

        // Try sessionManager.remove(handle); if not present, try removeTorrent(handle) via reflection.
        try {
            callMethodIfExists(sessionManager, "remove", new Class[]{TorrentHandle.class}, new Object[]{handle});
        } catch (Throwable t) {
            try {
                callMethodIfExists(sessionManager, "removeTorrent", new Class[]{TorrentHandle.class}, new Object[]{handle});
            } catch (Throwable t2) {
                Log.w(TAG, "No remove method available on SessionManager: " + t2.getMessage());
            }
        }

        Log.d(TAG, "Cleaned up and removed torrent for request ID: " + (dropRequestId != null ? dropRequestId : "unknown"));
    }

    public void stopSession() {
        Log.d(TAG, "Stopping torrent session manager.");
        sessionManager.stop();
        activeTorrents.clear();
        hashToIdMap.clear();
        instance = null;
    }

    // --------------------------
    // Reflection & helper utils
    // --------------------------

    private TorrentHandle tryDownloadViaReflection(TorrentInfo ti, File saveDir) {
        // Try common method signatures and return a TorrentHandle if possible.
        try {
            // Attempt: TorrentHandle download(TorrentInfo, File)
            Method m = findMethod(sessionManager.getClass(), "download", new Class[]{TorrentInfo.class, File.class});
            if (m != null) {
                Object r = m.invoke(sessionManager, ti, saveDir);
                if (r instanceof TorrentHandle) return (TorrentHandle) r;
                else if (r == null) {
                    // Some versions: method returns void; try to find the handle by searching session for torrent matching info
                    String hex = infoHashObjectToHexSafe(ti);
                    TorrentHandle h = findHandleByInfoHex(hex);
                    if (h != null) return h;
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            // Attempt: TorrentHandle download(String magnet, File, torrent_flags_t)
            Method m2 = findMethod(sessionManager.getClass(), "download", new Class[]{String.class, File.class, Object.class});
            if (m2 != null) {
                // try calling with null flags
                Object r = m2.invoke(sessionManager, ti.makeMagnetUri(), saveDir, null);
                if (r instanceof TorrentHandle) return (TorrentHandle) r;
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private TorrentHandle tryAddTorrentParams(AddTorrentParams params, File saveDir) {
        try {
            // Try: sessionManager.download(AddTorrentParams) -> some micro-versions may implement this
            Method m = findMethod(sessionManager.getClass(), "download", new Class[]{AddTorrentParams.class});
            if (m != null) {
                Object r = m.invoke(sessionManager, params);
                if (r instanceof TorrentHandle) return (TorrentHandle) r;
                else if (r == null) {
                    // void return path - try to locate handle by infoHash inside params
                    Object ti = callMethodIfExists(params, "torrentInfo");
                    String hex = infoHashObjectToHexSafe(ti);
                    TorrentHandle h = findHandleByInfoHex(hex);
                    if (h != null) return h;
                }
            }
        } catch (Throwable ignored) {
        }

        // Try: sessionManager.addTorrent(AddTorrentParams) or add_torrent
        try {
            Method mAdd = findMethod(sessionManager.getClass(), "addTorrent", new Class[]{AddTorrentParams.class});
            if (mAdd != null) {
                Object r = mAdd.invoke(sessionManager, params);
                if (r instanceof TorrentHandle) return (TorrentHandle) r;
                else {
                    String hex = infoHashFromParamsHex(params);
                    return findHandleByInfoHex(hex);
                }
            }
        } catch (Throwable ignored) {
        }

        // If nothing returned, null
        return null;
    }

    private TorrentHandle tryAddTorrentParams(AddTorrentParams params, File saveDir, boolean _unused) {
        // wrapper kept for compatibility
        return tryAddTorrentParams(params, saveDir);
    }

    private String infoHashFromParamsHex(AddTorrentParams params) {
        try {
            Object ti = callMethodIfExists(params, "torrentInfo");
            return infoHashObjectToHexSafe(ti);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private TorrentHandle findHandleByInfoHex(String hex) {
        if (hex == null || hex.isEmpty()) return null;
        // naive search through activeTorrents map
        for (Map.Entry<String, TorrentHandle> e : activeTorrents.entrySet()) {
            TorrentHandle th = e.getValue();
            String hHex = extractInfoHashHexFromHandle(th);
            if (hHex != null && hHex.equalsIgnoreCase(hex)) return th;
        }
        // last-resort: attempt to inspect sessionManager for handles (reflectively)
        try {
            Object session = sessionManager;
            Method getTorrents = findMethod(session.getClass(), "getTorrents", new Class[]{});
            if (getTorrents != null) {
                Object list = getTorrents.invoke(session);
                if (list instanceof java.util.Collection) {
                    for (Object o : ((java.util.Collection) list)) {
                        try {
                            String hx = infoHashObjectToHexSafe(callMethodIfExists(o, "infoHash"));
                            if (hx != null && hx.equalsIgnoreCase(hex)) {
                                if (o instanceof TorrentHandle) return (TorrentHandle) o;
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private String extractInfoHashHexFromStatus(TorrentStatus status) {
        if (status == null) return null;
        try {
            // Try common accessor names
            try {
                Object ih = callMethodIfExists(status, "infoHash");
                String s = infoHashObjectToHexSafe(ih);
                if (s != null) return s;
            } catch (Throwable ignored) {}
            try {
                Object ih = callMethodIfExists(status, "info_hash");
                String s = infoHashObjectToHexSafe(ih);
                if (s != null) return s;
            } catch (Throwable ignored) {}
            // Some status objects give a torrent handle
            try {
                Object th = callMethodIfExists(status, "handle");
                if (th instanceof TorrentHandle) {
                    return extractInfoHashHexFromHandle((TorrentHandle) th);
                }
            } catch (Throwable ignored) {}
            // fallback to status.toString
            try {
                String s = status.toString();
                if (s != null && s.length() >= 20) return s;
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            Log.w(TAG, "extractInfoHashHexFromStatus failed: " + t.getMessage());
        }
        return null;
    }

    private String extractInfoHashHexFromHandle(TorrentHandle handle) {
        if (handle == null) return null;
        try {
            try {
                Object ih = callMethodIfExists(handle, "infoHash");
                String s = infoHashObjectToHexSafe(ih);
                if (s != null) return s;
            } catch (Throwable ignored) {}
            try {
                Object ih = callMethodIfExists(handle, "info_hash");
                String s = infoHashObjectToHexSafe(ih);
                if (s != null) return s;
            } catch (Throwable ignored) {}
            // fallback to handle.toString()
            try {
                String s = handle.toString();
                if (s != null && s.length() >= 20) return s;
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            Log.w(TAG, "extractInfoHashHexFromHandle failed: " + t.getMessage());
        }
        return null;
    }

    private long safeLong(Object obj, String methodName) {
        try {
            Object v = callMethodIfExists(obj, methodName);
            if (v instanceof Number) return ((Number) v).longValue();
        } catch (Throwable ignored) {}
        return 0L;
    }

    private Object callMethodIfExists(Object target, String methodName, Class[] paramTypes, Object[] params) throws Exception {
        if (target == null) return null;
        Method m = findMethod(target.getClass(), methodName, paramTypes);
        if (m == null) return null;
        m.setAccessible(true);
        return m.invoke(target, params);
    }

    private Object callMethodIfExists(Object target, String methodName) throws Exception {
        return callMethodIfExists(target, methodName, new Class[]{}, new Object[]{});
    }

    private Object callStaticMethodIfExists(Class<?> cls, String methodName, Class[] paramTypes, Object[] params) throws Exception {
        Method m = findMethod(cls, methodName, paramTypes);
        if (m == null) return null;
        m.setAccessible(true);
        return m.invoke(null, params);
    }

    private Method findMethod(Class<?> cls, String name, Class[] paramTypes) {
        if (cls == null) return null;
        try {
            return cls.getMethod(name, paramTypes);
        } catch (NoSuchMethodException e) {
            // try declared methods
            for (Method mm : cls.getDeclaredMethods()) {
                if (!mm.getName().equals(name)) continue;
                Class<?>[] pts = mm.getParameterTypes();
                if (paramTypes == null || paramTypes.length == 0 || pts.length == paramTypes.length) {
                    return mm;
                }
            }
            // check superclasses
            Class<?> sc = cls.getSuperclass();
            if (sc != null) return findMethod(sc, name, paramTypes);
            return null;
        }
    }

    private Constructor<?> findConstructor(Class<?> cls, Class[] paramTypes) {
        try {
            return cls.getConstructor(paramTypes);
        } catch (NoSuchMethodException e) {
            for (Constructor<?> c : cls.getConstructors()) {
                Class<?>[] pts = c.getParameterTypes();
                if (pts.length == paramTypes.length) return c;
            }
            return null;
        }
    }

    private Object callMethodSafely(Object target, String name) {
        try {
            return callMethodIfExists(target, name);
        } catch (Throwable t) {
            return null;
        }
    }

    private void callMethodIfExists(Object target, String name, Class[] pts, Object[] args) {
        try {
            callMethodIfExists(target, name, pts, args);
        } catch (Throwable ignored) {
        }
    }

    private Object callStaticMethodIfExists(Class<?> cls, String name, Class[] pts, Object[] args) {
        try {
            return callStaticMethodIfExists(cls, name, pts, args);
        } catch (Throwable t) {
            return null;
        }
    }

    private String infoHashObjectToHexSafe(Object ihObj) {
        if (ihObj == null) return null;
        try {
            // toHex()
            try {
                Method m = findMethod(ihObj.getClass(), "toHex", new Class[]{});
                if (m != null) {
                    Object r = m.invoke(ihObj);
                    if (r instanceof String) return (String) r;
                }
            } catch (Throwable ignored) {}
            // toString()
            try {
                String s = ihObj.toString();
                if (s != null && s.length() > 0) return s;
            } catch (Throwable ignored) {}
            // if there is a getBytes() or data() method returning byte[]
            try {
                Method m = findMethod(ihObj.getClass(), "getBytes", new Class[]{});
                if (m != null) {
                    Object r = m.invoke(ihObj);
                    if (r instanceof byte[]) return bytesToHex((byte[]) r);
                }
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            Log.w(TAG, "infoHashObjectToHexSafe failed: " + t.getMessage());
        }
        return null;
    }

    private byte[] attemptByteVectorToBytesReflective(Object byteVectorObj) {
        if (byteVectorObj == null) return null;
        try {
            // Try size() and get(i)
            Method sizeM = findMethod(byteVectorObj.getClass(), "size", new Class[]{});
            Method getM = findMethod(byteVectorObj.getClass(), "get", new Class[]{int.class});
            if (sizeM != null && getM != null) {
                Object szObj = sizeM.invoke(byteVectorObj);
                int sz = (szObj instanceof Number) ? ((Number) szObj).intValue() : 0;
                byte[] out = new byte[sz];
                for (int i = 0; i < sz; i++) {
                    Object b = getM.invoke(byteVectorObj, i);
                    if (b instanceof Number) out[i] = ((Number) b).byteValue();
                    else out[i] = (byte) ((int) b);
                }
                return out;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private String bytesToHex(byte[] bytes) {
        if (bytes == null) return null;
        final char[] HEX_ARRAY = "0123456789abcdef".toCharArray();
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }
}
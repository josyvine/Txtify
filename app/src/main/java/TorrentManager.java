package com.hfm.app;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.libtorrent4j.AddTorrentParams;
import org.libtorrent4j.AlertListener;
import org.libtorrent4j.ErrorCode;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.Sha1Hash;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.alerts.Alert;
import org.libtorrent4j.alerts.StateUpdateAlert;
import org.libtorrent4j.alerts.TorrentErrorAlert;
import org.libtorrent4j.alerts.TorrentFinishedAlert;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TorrentManager {

    private static final String TAG = "TorrentManager";
    private static volatile TorrentManager instance;

    private final SessionManager sessionManager;
    private final Context appContext;

    private final Map<String, TorrentHandle> activeTorrents; // dropRequestId -> TorrentHandle
    private final Map<Sha1Hash, String> hashToIdMap; // infoHash -> dropRequestId

    private TorrentManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.sessionManager = new SessionManager();
        this.activeTorrents = new ConcurrentHashMap<>();
        this.hashToIdMap = new ConcurrentHashMap<>();

        sessionManager.addListener(new AlertListener() {
            @Override
            public int[] types() {
                // Returning null listens to all alerts (simpler & safer with new API)
                return null;
            }

            @Override
            public void alert(Alert<?> alert) {
                if (alert instanceof StateUpdateAlert) {
                    handleStateUpdate((StateUpdateAlert) alert);
                } else if (alert instanceof TorrentFinishedAlert) {
                    handleTorrentFinished((TorrentFinishedAlert) alert);
                } else if (alert instanceof TorrentErrorAlert) {
                    handleTorrentError((TorrentErrorAlert) alert);
                }
            }
        });

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
        for (TorrentStatus status : alert.status()) {
            String dropRequestId = hashToIdMap.get(status.infoHash());
            if (dropRequestId != null) {
                Intent intent = new Intent(DropProgressActivity.ACTION_UPDATE_STATUS);
                intent.putExtra(DropProgressActivity.EXTRA_STATUS_MAJOR,
                        status.isSeeding() ? "Sending File..." : "Receiving File...");
                intent.putExtra(DropProgressActivity.EXTRA_STATUS_MINOR,
                        "Peers: " + status.numPeers()
                                + " | ↓ " + (status.downloadPayloadRate() / 1024) + " KB/s"
                                + " | ↑ " + (status.uploadPayloadRate() / 1024) + " KB/s");
                intent.putExtra(DropProgressActivity.EXTRA_PROGRESS, (int) status.totalDone());
                intent.putExtra(DropProgressActivity.EXTRA_MAX_PROGRESS, (int) status.totalWanted());
                intent.putExtra(DropProgressActivity.EXTRA_BYTES_TRANSFERRED, status.totalDone());
                LocalBroadcastManager.getInstance(appContext).sendBroadcast(intent);
            }
        }
    }

    private void handleTorrentFinished(TorrentFinishedAlert alert) {
        TorrentHandle handle = alert.handle();
        String dropRequestId = hashToIdMap.get(handle.infoHash());
        Log.d(TAG, "Torrent finished for request ID: " + dropRequestId);

        if (dropRequestId != null) {
            Intent intent = new Intent(DropProgressActivity.ACTION_TRANSFER_COMPLETE);
            LocalBroadcastManager.getInstance(appContext).sendBroadcast(intent);
        }

        cleanupTorrent(handle);
    }

    private void handleTorrentError(TorrentErrorAlert alert) {
        TorrentHandle handle = alert.handle();
        String dropRequestId = hashToIdMap.get(handle.infoHash());
        String errorMsg = alert.message();
        Log.e(TAG, "Torrent error for request ID " + dropRequestId + ": " + errorMsg);

        if (dropRequestId != null) {
            Intent errorIntent = new Intent(DownloadService.ACTION_DOWNLOAD_ERROR);
            errorIntent.putExtra(DownloadService.EXTRA_ERROR_MESSAGE,
                    "Torrent transfer failed: " + errorMsg);
            LocalBroadcastManager.getInstance(appContext).sendBroadcast(errorIntent);
            LocalBroadcastManager.getInstance(appContext)
                    .sendBroadcast(new Intent(DropProgressActivity.ACTION_TRANSFER_ERROR));
        }

        cleanupTorrent(handle);
    }

    public String startSeeding(File file, String dropRequestId) {
        if (file == null || !file.exists()) {
            Log.e(TAG, "File to be seeded does not exist.");
            return null;
        }

        TorrentInfo torrentInfo = new TorrentInfo(file);

        AddTorrentParams params = AddTorrentParams.builder(torrentInfo)
                .savePath(file.getParentFile().getAbsolutePath())
                .build();

        TorrentHandle handle = sessionManager.addTorrent(params);

        if (handle != null) {
            activeTorrents.put(dropRequestId, handle);
            hashToIdMap.put(handle.infoHash(), dropRequestId);
            String magnetLink = handle.makeMagnetUri();
            Log.d(TAG, "Started seeding for request ID " + dropRequestId + ". Magnet: " + magnetLink);
            return magnetLink;
        } else {
            Log.e(TAG, "Failed to get TorrentHandle after adding seed.");
            return null;
        }
    }

    public void startDownload(String magnetLink, File saveDirectory, String dropRequestId) {
        if (!saveDirectory.exists()) {
            saveDirectory.mkdirs();
        }

        AddTorrentParams params = AddTorrentParams.parseMagnetUri(magnetLink)
                .withSavePath(saveDirectory.getAbsolutePath());

        TorrentHandle handle = sessionManager.addTorrent(params);

        if (handle != null) {
            activeTorrents.put(dropRequestId, handle);
            hashToIdMap.put(handle.infoHash(), dropRequestId);
            Log.d(TAG, "Started download for request ID: " + dropRequestId);
        } else {
            Log.e(TAG, "Failed to get TorrentHandle after adding download from magnet link.");
        }
    }

    private void cleanupTorrent(TorrentHandle handle) {
        if (handle == null || !handle.isValid()) {
            return;
        }

        Sha1Hash hash = handle.infoHash();
        String dropRequestId = hashToIdMap.get(hash);

        if (dropRequestId != null) {
            activeTorrents.remove(dropRequestId);
            hashToIdMap.remove(hash);
        }

        sessionManager.removeTorrent(handle);
        Log.d(TAG, "Cleaned up and removed torrent for request ID: " + dropRequestId);
    }

    public void stopSession() {
        Log.d(TAG, "Stopping torrent session manager.");
        sessionManager.stop();
        activeTorrents.clear();
        hashToIdMap.clear();
        instance = null;
    }
}
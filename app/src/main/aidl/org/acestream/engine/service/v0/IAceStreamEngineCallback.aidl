package org.acestream.engine.service.v0;

/**
 * Ace Stream Engine callback interface
 */
oneway interface IAceStreamEngineCallback {

    /**
     * Engine unpacking (this happens after installation and after engine was updated).
     */
    void onUnpacking();

    /**
     * Engine starting.
     */
    void onStarting();

    /**
     * Engine started successfully and listens @listenPort
     * or failed to start,  if @listenPort == -1.
     */
    void onReady(int listenPort);

    /**
     * Engine stopped.
     */
    void onStopped();

    /**
     * Deprecated method. It is not called anymore.
     */
    void onWaitForNetworkConnection();

    /**
     * Media server event: playlist was updated.
     */
    void onPlaylistUpdated();

    /**
     * Media server event: EPG was updated.
     */
    void onEPGUpdated();

    /**
     * Client should restart player (stop playback and start again with the same playback URI).
     */
    void onRestartPlayer();

    /**
     * Engine settings were updated.
     */
    void onSettingsUpdated();

    /**
     * Auth level updated.
     */
    void onAuthUpdated();
}
package org.acestream.engine.service.v0;

import org.acestream.engine.service.v0.IAceStreamEngineCallback;
import org.acestream.engine.service.v0.IStartEngineResponse;

/**
 * Ace Stream Engine service interface
 */
interface IAceStreamEngine {

    /**
     * Register a client.
     */
    void registerCallback(IAceStreamEngineCallback cb);

    /**
     * Unregister a client.
     */
    void unregisterCallback(IAceStreamEngineCallback cb);

    /**
     * Start engine.
     */
    void startEngine();

    /**
     * Internal method. Not for public usage.
     */
    void registerCallbackExt(IAceStreamEngineCallback cb, boolean skipMobileNetworksCheck);

    /**
     * Start engine and receive result via callback.
     * NOTE: currently callback will be called only when engine is already started at the moment
     * of calling this method. You should rely on IAceStreamEngineCallback.onReady() event.
     */
    void startEngineWithCallback(IStartEngineResponse callback);

    /**
     * Get Engine API port.
     * Returns 0 if engine is not started.
     * @see http://wiki.acestream.org/wiki/index.php/Engine_API
     */
    int getEngineApiPort();

    /**
     * Get HTTP API port.
     * Returns 0 if engine is not started.
     * @see http://wiki.acestream.org/wiki/index.php/Engine_HTTP_API
     */
    int getHttpApiPort();

    /**
     * Internal method. Not for public usage.
     */
    String getAccessToken();

    /**
     * Start AceCast server.
     */
    void enableAceCastServer();
}
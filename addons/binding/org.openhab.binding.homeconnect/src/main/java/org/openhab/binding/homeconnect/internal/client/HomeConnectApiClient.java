/**
 * Copyright (c) 2010-2018 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.homeconnect.internal.client;

import static java.net.HttpURLConnection.*;
import static org.apache.commons.lang.StringUtils.isEmpty;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.openhab.binding.homeconnect.handler.HomeConnectBridgeHandler;
import org.openhab.binding.homeconnect.internal.client.exception.CommunicationException;
import org.openhab.binding.homeconnect.internal.client.exception.ConfigurationException;
import org.openhab.binding.homeconnect.internal.client.listener.ServerSentEventListener;
import org.openhab.binding.homeconnect.internal.client.model.Data;
import org.openhab.binding.homeconnect.internal.client.model.Event;
import org.openhab.binding.homeconnect.internal.client.model.HomeAppliance;
import org.openhab.binding.homeconnect.internal.client.model.Option;
import org.openhab.binding.homeconnect.internal.client.model.Program;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.here.oksse.OkSse;
import com.here.oksse.ServerSentEvent;

import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Client for Home Connect API.
 *
 * @author Jonas Brüstel - Initial contribution
 *
 */
public class HomeConnectApiClient {
    private final static String API_URL = "https://api.home-connect.com";
    private final static String API_SIMULATOR_URL = "https://simulator.home-connect.com";
    private final static String ACCEPT = "Accept";
    private final static String BSH_JSON_V1 = "application/vnd.bsh.sdk.v1+json";
    private final static String KEEP_ALIVE = "KEEP-ALIVE";
    private final static String AUTH_DEFAULT_REDIRECT_URL = "https://apiclient.home-connect.com/o2c.html";
    private final static String AUTH_URI_PATH = "/security/oauth/authorize";
    private final static String AUTH_CLIENT_ID = "client_id";
    private final static String AUTH_REDIRECT_URI = "redirect_uri";
    private final static String AUTH_SCOPE = "scope";
    private final static String AUTH_CODE_GRAND_SCOPE_VALUE = "IdentifyAppliance Monitor Settings";

    private final Logger logger = LoggerFactory.getLogger(HomeConnectBridgeHandler.class);
    private OkHttpClient client;
    private String apiUrl;

    private String clientId, clientSecret, token, refreshToken;
    private boolean simulated;

    private final ArrayList<ServerSentEventListener> eventListeners;
    private final HashMap<String, ServerSentEvent> serverSentEvent;

    private OkSse oksse;

    public HomeConnectApiClient(String clientId, String clientSecret, String token, String refreshToken,
            boolean simulated) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.token = token;
        this.refreshToken = refreshToken;
        this.simulated = simulated;

        eventListeners = new ArrayList<>();
        serverSentEvent = new HashMap<>();

        // setup http client
        client = new OkHttpClient();
        apiUrl = simulated ? API_SIMULATOR_URL : API_URL;

        // configure Server Sent Event client
        oksse = new OkSse();
    }

    /**
     * Get all home appliances
     *
     * @return list of {@link HomeAppliance} or null in case of communication error
     * @throws ConfigurationException
     * @throws CommunicationException
     */
    public List<HomeAppliance> getHomeAppliances() throws ConfigurationException, CommunicationException {
        checkCredentials();

        Request request = new Request.Builder().url(apiUrl + "/api/homeappliances").header(ACCEPT, BSH_JSON_V1).get()
                .addHeader("Authorization", "Bearer " + token).build();

        try {
            Response response = client.newCall(request).execute();
            String body = response.body().string();
            logger.debug("[getHomeAppliances()] Response code: {}, body: {}", response.code(), body);

            if (response.code() == HTTP_OK) {
                return mapToHomeAppliances(body);
            }
        } catch (IOException e) {
            logger.error("Token does not work!", e);
        }

        return null;
    }

    /**
     * Get home appliance by id
     *
     * @param haId home appliance id
     * @return {@link HomeAppliance} or null in case of communication error
     * @throws ConfigurationException
     * @throws CommunicationException
     */
    public HomeAppliance getHomeAppliance(String haId) throws ConfigurationException, CommunicationException {
        checkCredentials();

        Request request = new Request.Builder().url(apiUrl + "/api/homeappliances/" + haId).header(ACCEPT, BSH_JSON_V1)
                .get().addHeader("Authorization", "Bearer " + token).build();

        try {
            Response response = client.newCall(request).execute();
            String body = response.body().string();
            logger.debug("[getHomeAppliance({})] Response code: {}, body: {}", haId, response.code(), body);

            if (response.code() == HTTP_OK) {
                return mapToHomeAppliance(body);
            }
        } catch (IOException e) {
            logger.error("Token does not work!", e);
        }

        return null;
    }

    /**
     * Get power state of device.
     *
     * @param haId home appliance id
     * @return {@link Data} or null in case of communication error
     * @throws CommunicationException
     * @throws ConfigurationException
     */
    public Data getPowerState(String haId) throws ConfigurationException, CommunicationException {
        return getSetting(haId, "BSH.Common.Setting.PowerState");
    }

    /**
     * Set power state of device.
     *
     * @param haId  home appliance id
     * @param state target state
     * @throws CommunicationException
     * @throws ConfigurationException
     */
    public void setPowerState(String haId, String state) throws ConfigurationException, CommunicationException {
        putSettings(haId, new Data("BSH.Common.Setting.PowerState", state));
    }

    /**
     * Get door state of device.
     *
     * @param haId home appliance id
     * @return {@link Data} or null in case of communication error
     * @throws CommunicationException
     * @throws ConfigurationException
     */
    public Data getDoorState(String haId) throws ConfigurationException, CommunicationException {
        return getStatus(haId, "BSH.Common.Status.DoorState");
    }

    /**
     * Get operation state of device.
     *
     * @param haId home appliance id
     * @return {@link Data} or null in case of communication error
     * @throws CommunicationException
     * @throws ConfigurationException
     */
    public Data getOperationState(String haId) throws ConfigurationException, CommunicationException {
        return getStatus(haId, "BSH.Common.Status.OperationState");
    }

    /**
     * Is remote start allowed?
     *
     * @param haId haId home appliance id
     * @return
     * @throws CommunicationException
     * @throws ConfigurationException
     */
    public boolean isRemoteControlStartAllowed(String haId) throws ConfigurationException, CommunicationException {
        Data data = getStatus(haId, "BSH.Common.Status.RemoteControlStartAllowed");
        return data != null && "true".equalsIgnoreCase(data.getValue());
    }

    /**
     * Is remote control allowed?
     *
     * @param haId haId home appliance id
     * @return
     * @throws CommunicationException
     * @throws ConfigurationException
     */
    public boolean isRemoteControlActive(String haId) throws ConfigurationException, CommunicationException {
        Data data = getStatus(haId, "BSH.Common.Status.RemoteControlActive");
        return data != null && "true".equalsIgnoreCase(data.getValue());
    }

    /**
     * Get active program of device.
     *
     * @param haId home appliance id
     * @return {@link Data} or null in case of communication error or if there is no active program
     * @throws CommunicationException
     * @throws ConfigurationException
     */
    public Program getActiveProgram(String haId) throws ConfigurationException, CommunicationException {
        return getProgram(haId, "/api/homeappliances/" + haId + "/programs/active");
    }

    /**
     * Register {@link ServerSentEventListener} to receive SSE events by Home Conncet API. This helps to reduce the
     * amount of request you would usually need to update all channels.
     *
     * Checkout rate limits of the API at. https://developer.home-connect.com/docs/general/ratelimiting
     *
     * @param eventListener
     * @throws CommunicationException
     * @throws ConfigurationException
     */
    public synchronized void registerEventListener(ServerSentEventListener eventListener)
            throws ConfigurationException, CommunicationException {
        String haId = eventListener.haId();

        eventListeners.add(eventListener);

        if (!serverSentEvent.containsKey(haId)) {
            checkCredentials();
            Request request = new Request.Builder().url(apiUrl + "/api/homeappliances/" + haId + "/events")
                    .addHeader("Authorization", "Bearer " + token).build();

            ServerSentEvent sse = oksse.newServerSentEvent(request, new ServerSentEvent.Listener() {

                @Override
                public void onOpen(ServerSentEvent sse, Response response) {
                    logger.debug("[{}] SSE channel opened", haId);
                }

                @Override
                public void onMessage(ServerSentEvent sse, String id, String event, String message) {
                    if (logger.isDebugEnabled()) {
                        if (KEEP_ALIVE.equals(event)) {
                            logger.debug("[{}] SSE KEEP-ALIVE", haId);
                        } else {
                            logger.debug("[{}] SSE received id: {} event: {} message:{}", haId, id, event, message);
                        }
                    }

                    if (!isEmpty(message)) {
                        ArrayList<Event> events = mapToEvents(message);
                        events.forEach(e -> eventListeners.forEach(listener -> {
                            if (listener.haId().equals(haId)) {
                                listener.onEvent(e);
                            }
                        }));
                    }
                }

                @Override
                public void onComment(ServerSentEvent sse, String comment) {
                    logger.debug("[{}] SSE comment received comment: {}", haId, comment);
                }

                @Override
                public boolean onRetryTime(ServerSentEvent sse, long milliseconds) {
                    return true; // True to use the new retry time received by SSE
                }

                @Override
                public boolean onRetryError(ServerSentEvent sse, Throwable throwable, Response response) {
                    return true; // True to retry, false otherwise
                }

                @Override
                public void onClosed(ServerSentEvent sse) {
                    // Channel closed
                }

                @Override
                public Request onPreRetry(ServerSentEvent sse, Request request) {
                    eventListeners.forEach(listener -> {
                        if (listener.haId().equals(haId)) {
                            listener.onReconnect();
                        }
                    });
                    return request;
                }
            });
            serverSentEvent.put(haId, sse);
        }
    }

    /**
     * Unregister {@link ServerSentEventListener}.
     *
     * @param eventListener
     */
    public synchronized void unregisterEventListener(ServerSentEventListener eventListener) {
        eventListeners.remove(eventListener);
        String haId = eventListener.haId();

        // remove unused SSE connections
        boolean needToRemoveSse = true;
        for (ServerSentEventListener el : eventListeners) {
            if (el.haId().equals(haId)) {
                needToRemoveSse = false;
            }
        }
        if (needToRemoveSse && serverSentEvent.containsKey(haId)) {
            serverSentEvent.get(haId).close();
            serverSentEvent.remove(haId);
        }
    }

    /**
     * Dispose and shutdown API client.
     */
    public synchronized void dispose() {
        serverSentEvent.forEach((key, value) -> value.close());
        serverSentEvent.clear();

        eventListeners.clear();
    }

    private Data getSetting(String haId, String setting) throws ConfigurationException, CommunicationException {
        return getData(haId, "/api/homeappliances/" + haId + "/settings/" + setting);
    }

    private void putSettings(String haId, Data data) throws ConfigurationException, CommunicationException {
        putData(haId, "/api/homeappliances/" + haId + "/settings/" + data.getName(), data);
    }

    private Data getStatus(String haId, String status) throws ConfigurationException, CommunicationException {
        return getData(haId, "/api/homeappliances/" + haId + "/status/" + status);
    }

    private Program getProgram(String haId, String path) throws ConfigurationException, CommunicationException {
        checkCredentials();

        Request request = new Request.Builder().url(apiUrl + path).header(ACCEPT, BSH_JSON_V1).get()
                .addHeader("Authorization", "Bearer " + token).build();

        try {
            Response response = client.newCall(request).execute();
            String body = response.body().string();
            logger.debug("[getProgram({}, {})] Response code: {}, body: {}", haId, path, response.code(), body);

            if (response.code() == HTTP_OK) {
                return mapToProgram(body);
            }
        } catch (IOException e) {
            logger.error("Token does not work!", e);
        }

        return null;
    }

    private Data getData(String haId, String path) throws ConfigurationException, CommunicationException {
        checkCredentials();

        Request request = new Request.Builder().url(apiUrl + path).header(ACCEPT, BSH_JSON_V1).get()
                .addHeader("Authorization", "Bearer " + token).build();

        try {
            Response response = client.newCall(request).execute();
            String body = response.body().string();
            logger.debug("[getData({}, {})] Response code: {}, body: {}", haId, path, response.code(), body);

            if (response.code() == HTTP_OK) {
                return mapToState(body);
            }
        } catch (IOException e) {
            logger.error("Token does not work!", e);
        }

        return null;
    }

    private void putData(String haId, String path, Data data) throws ConfigurationException, CommunicationException {
        JsonObject innerObject = new JsonObject();
        innerObject.addProperty("key", data.getName());
        innerObject.addProperty("value", data.getValue());
        JsonObject dataObject = new JsonObject();
        dataObject.add("data", innerObject);

        MediaType JSON = MediaType.parse(BSH_JSON_V1);
        RequestBody requestBody = RequestBody.create(JSON, dataObject.toString());

        checkCredentials();
        Request request = new Request.Builder().url(apiUrl + path).header(ACCEPT, BSH_JSON_V1).put(requestBody)
                .addHeader("Authorization", "Bearer " + token).build();

        try {
            Response response = client.newCall(request).execute();
            String body = response.body().string();
            logger.debug("[putData({}, {}, {})] Response code: {} body: {}", haId, path, data, response.code(), body);

            if (response.code() == 204) {
            }

            // TODO handly non 204 responses
        } catch (IOException e) {
            logger.error("Token does not work!", e);
        }
    }

    private synchronized void checkCredentials() throws ConfigurationException, CommunicationException {
        if (simulated) {
            if (isEmpty(token)) {
                authorize();
            }
        } else {
            if (isEmpty(refreshToken)) {
                logger.error("No refresh token present!");
                throw new ConfigurationException("No refresh token set!");
            } else {
                if (isEmpty(token)) {
                    refreshToken();
                }
            }
        }
    }

    /**
     * Authorize (Authorization Code Grant Flow)
     * Works only with clientId from simulator.
     *
     * @throws CommunicationException
     */
    private void authorize() throws CommunicationException {
        logger.debug("[oAuth] Authorize (Authorization Code Grant Flow). client_id: {}", clientId);

        try {

            OkHttpClient client = new OkHttpClient().newBuilder().followRedirects(false).followSslRedirects(false)
                    .build();

            // step one - Authorization Request
            HttpUrl url = HttpUrl.parse(apiUrl + AUTH_URI_PATH).newBuilder().addQueryParameter(AUTH_CLIENT_ID, clientId)
                    .addQueryParameter("response_type", "code")
                    .addQueryParameter(AUTH_REDIRECT_URI, AUTH_DEFAULT_REDIRECT_URL)
                    .addQueryParameter(AUTH_SCOPE, AUTH_CODE_GRAND_SCOPE_VALUE).build();
            Request request = new Request.Builder().url(url).get().build();
            Response response;
            response = client.newCall(request).execute();
            if (response.code() != HTTP_MOVED_TEMP) {
                logger.error("[oAuth] Couldn't authorize against API! response: {}", response);
                int code = response.code();
                String body = response.body().string();
                String message = response.message();
                response.close();
                throw new CommunicationException(code, message, body);
            }
            HttpUrl location = HttpUrl.parse(response.header("Location"));
            String oAuthCode = location.queryParameter("code");
            logger.debug("[oAuth] Authorize (Authorization Code Grant Flow). http response code: {} oAuth code: {}",
                    response.code(), oAuthCode);
            response.close();

            // step two - Access Token Request
            RequestBody formBody = new FormBody.Builder().add(AUTH_CLIENT_ID, clientId)
                    .add("grant_type", "authorization_code").add(AUTH_REDIRECT_URI, AUTH_DEFAULT_REDIRECT_URL)
                    .add("code", oAuthCode).build();
            Request accessTokenRequest = new Request.Builder().url(apiUrl + "/security/oauth/token").post(formBody)
                    .build();
            Response accessTokenResponse = client.newCall(accessTokenRequest).execute();
            if (accessTokenResponse.code() != HTTP_OK) {
                logger.error("[oAuth] Couldn't get token! response: {}", response);
                int code = accessTokenResponse.code();
                String message = accessTokenResponse.message();
                String body = accessTokenResponse.body().string();
                accessTokenResponse.close();
                throw new CommunicationException(code, message, body);
            }
            String accessTokenResponseBody = accessTokenResponse.body().string();
            JsonObject responseObject = new JsonParser().parse(accessTokenResponseBody).getAsJsonObject();
            token = responseObject.get("access_token").getAsString();
            logger.debug("[oAuth] Access Token Request (Authorization Code Grant Flow).  token: {}", token);

        } catch (IOException e) {
            logger.error("Error accured while communicating with API!");
            throw new CommunicationException(e);
        }
    }

    /**
     * Refresh oAuth token with configured refresh token (Device Flow).
     *
     *
     * The configured refresh token will not expire if it used regularly (expires if it wasn't used within 2 months).
     */
    private void refreshToken() {
        logger.debug("Refreshing token. token: {} refresh_token: {}", token, refreshToken);

        // TODO handle non 200 responses
        // TODO handle exceptions
        // TODO handle retry in case of io error or 500

        try {
            RequestBody formBody = new FormBody.Builder().add("refresh_token", refreshToken)
                    .add("grant_type", "refresh_token").add("client_secret", clientSecret).build();

            Request request = new Request.Builder().url(apiUrl + "/security/oauth/token").post(formBody).build();
            String response = client.newCall(request).execute().body().string();

            logger.debug("refresh token response body {}.", response);

            JsonObject responseObject = new JsonParser().parse(response).getAsJsonObject();

            token = responseObject.get("access_token").getAsString();
        } catch (Exception e) {
            // TODO impl
        } finally {
        }
    }

    private Program mapToProgram(String json) {
        final ArrayList<Option> optionList = new ArrayList<>();
        Program result = null;

        JsonObject responseObject = new JsonParser().parse(json).getAsJsonObject();

        JsonObject data = responseObject.getAsJsonObject("data");
        result = new Program(data.get("key").getAsString(), optionList);
        JsonArray options = data.getAsJsonArray("options");

        options.forEach(option -> {
            JsonObject obj = (JsonObject) option;

            String key = obj.get("key") != null ? obj.get("key").getAsString() : null;
            String value = obj.get("value") != null && !obj.get("value").isJsonNull() ? obj.get("value").getAsString()
                    : null;
            String unit = obj.get("unit") != null ? obj.get("unit").getAsString() : null;

            optionList.add(new Option(key, value, unit));
        });

        return result;
    }

    private HomeAppliance mapToHomeAppliance(String json) {
        JsonObject responseObject = new JsonParser().parse(json).getAsJsonObject();

        JsonObject data = responseObject.getAsJsonObject("data");

        return new HomeAppliance(data.get("haId").getAsString(), data.get("name").getAsString(),
                data.get("brand").getAsString(), data.get("vib").getAsString(), data.get("connected").getAsBoolean(),
                data.get("type").getAsString(), data.get("enumber").getAsString());
    }

    private ArrayList<HomeAppliance> mapToHomeAppliances(String json) {
        final ArrayList<HomeAppliance> result = new ArrayList<>();
        JsonObject responseObject = new JsonParser().parse(json).getAsJsonObject();

        JsonObject data = responseObject.getAsJsonObject("data");
        JsonArray homeappliances = data.getAsJsonArray("homeappliances");

        homeappliances.forEach(appliance -> {
            JsonObject obj = (JsonObject) appliance;

            result.add(new HomeAppliance(obj.get("haId").getAsString(), obj.get("name").getAsString(),
                    obj.get("brand").getAsString(), obj.get("vib").getAsString(), obj.get("connected").getAsBoolean(),
                    obj.get("type").getAsString(), obj.get("enumber").getAsString()));
        });

        return result;
    }

    private Data mapToState(String json) {
        JsonObject responseObject = new JsonParser().parse(json).getAsJsonObject();

        JsonObject data = responseObject.getAsJsonObject("data");

        return new Data(data.get("key").getAsString(), data.get("value").getAsString());
    }

    private ArrayList<Event> mapToEvents(String json) {
        ArrayList<Event> events = new ArrayList<>();

        JsonObject responseObject = new JsonParser().parse(json).getAsJsonObject();
        JsonArray items = responseObject.getAsJsonArray("items");

        items.forEach(item -> {
            JsonObject obj = (JsonObject) item;
            String key = obj.get("key") != null ? obj.get("key").getAsString() : null;
            String value = obj.get("value") != null && !obj.get("value").isJsonNull() ? obj.get("value").getAsString()
                    : null;
            String unit = obj.get("unit") != null ? obj.get("unit").getAsString() : null;

            events.add(new Event(key, value, unit));
        });

        return events;
    }

}
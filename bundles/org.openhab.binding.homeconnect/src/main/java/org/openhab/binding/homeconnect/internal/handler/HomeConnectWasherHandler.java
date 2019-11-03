/**
 * Copyright (c) 2010-2019 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.homeconnect.internal.handler;

import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.*;

import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.UnDefType;
import org.openhab.binding.homeconnect.internal.client.exception.AuthorizationException;
import org.openhab.binding.homeconnect.internal.client.exception.CommunicationException;
import org.openhab.binding.homeconnect.internal.client.model.Program;
import org.openhab.binding.homeconnect.internal.logger.EmbeddedLoggingService;
import org.openhab.binding.homeconnect.internal.logger.Logger;
import org.openhab.binding.homeconnect.internal.type.HomeConnectDynamicStateDescriptionProvider;

import jersey.repackaged.com.google.common.collect.ImmutableList;

/**
 * The {@link HomeConnectWasherHandler} is responsible for handling commands, which are
 * sent to one of the channels of a washing machine.
 *
 * @author Jonas Brüstel - Initial contribution
 */
@NonNullByDefault
public class HomeConnectWasherHandler extends AbstractHomeConnectThingHandler {

    private static final ImmutableList<String> ACTIVE_STATE = ImmutableList.of(OPERATION_STATE_DELAYED_START,
            OPERATION_STATE_RUN, OPERATION_STATE_PAUSE);
    private static final ImmutableList<String> INACTIVE_STATE = ImmutableList.of(OPERATION_STATE_INACTIVE,
            OPERATION_STATE_READY);

    private final Logger logger;

    public HomeConnectWasherHandler(Thing thing,
            HomeConnectDynamicStateDescriptionProvider dynamicStateDescriptionProvider,
            EmbeddedLoggingService loggingService) {
        super(thing, dynamicStateDescriptionProvider, loggingService);
        logger = loggingService.getLogger(HomeConnectWasherDryerHandler.class);
    }

    @Override
    protected void configureChannelUpdateHandlers(ConcurrentHashMap<String, ChannelUpdateHandler> handlers) {
        // register default update handlers
        handlers.put(CHANNEL_DOOR_STATE, defaultDoorStateChannelUpdateHandler());
        handlers.put(CHANNEL_OPERATION_STATE, defaultOperationStateChannelUpdateHandler());
        handlers.put(CHANNEL_REMOTE_CONTROL_ACTIVE_STATE, defaultRemoteControlActiveStateChannelUpdateHandler());
        handlers.put(CHANNEL_REMOTE_START_ALLOWANCE_STATE, defaultRemoteStartAllowanceChannelUpdateHandler());
        handlers.put(CHANNEL_LOCAL_CONTROL_ACTIVE_STATE, defaultLocalControlActiveStateChannelUpdateHandler());
        handlers.put(CHANNEL_ACTIVE_PROGRAM_STATE, defaultActiveProgramStateUpdateHandler());
        handlers.put(CHANNEL_SELECTED_PROGRAM_STATE, updateProgramOptionsAndSelectedProgramStateUpdateHandler());

        // register washer specific handlers
        handlers.put(CHANNEL_WASHER_SPIN_SPEED, (channelUID, client, cache) -> {
            Program program = client.getSelectedProgram(getThingHaId());
            if (program != null && program.getKey() != null) {
                updateProgramOptions(program.getKey());
            }
        });
        handlers.put(CHANNEL_WASHER_TEMPERATURE, (channelUID, client, cache) -> {
            Program program = client.getSelectedProgram(getThingHaId());
            if (program != null && program.getKey() != null) {
                updateProgramOptions(program.getKey());
            }
        });
    }

    @Override
    protected void configureEventHandlers(ConcurrentHashMap<String, EventHandler> handlers) {
        // register default event handlers
        handlers.put(EVENT_DOOR_STATE, defaultDoorStateEventHandler());
        handlers.put(EVENT_REMOTE_CONTROL_ACTIVE, defaultBooleanEventHandler(CHANNEL_REMOTE_CONTROL_ACTIVE_STATE));
        handlers.put(EVENT_REMOTE_CONTROL_START_ALLOWED,
                defaultBooleanEventHandler(CHANNEL_REMOTE_START_ALLOWANCE_STATE));
        handlers.put(EVENT_REMAINING_PROGRAM_TIME, defaultRemainingProgramTimeEventHandler());
        handlers.put(EVENT_PROGRAM_PROGRESS, defaultProgramProgressEventHandler());
        handlers.put(EVENT_LOCAL_CONTROL_ACTIVE, defaultBooleanEventHandler(CHANNEL_LOCAL_CONTROL_ACTIVE_STATE));
        handlers.put(EVENT_ACTIVE_PROGRAM, defaultActiveProgramEventHandler());
        handlers.put(EVENT_OPERATION_STATE, defaultOperationStateEventHandler());
        handlers.put(EVENT_SELECTED_PROGRAM, updateProgramOptionsAndSelectedProgramStateEventHandler());

        // register washer specific event handlers
        handlers.put(EVENT_WASHER_TEMPERATURE, event -> {
            getThingChannel(CHANNEL_WASHER_TEMPERATURE).ifPresent(channel -> {
                updateState(channel.getUID(),
                        event.getValue() == null ? UnDefType.NULL : new StringType(event.getValue()));
            });
        });
        handlers.put(EVENT_WASHER_SPIN_SPEED, event -> {
            getThingChannel(CHANNEL_WASHER_SPIN_SPEED).ifPresent(channel -> {
                updateState(channel.getUID(),
                        event.getValue() == null ? UnDefType.NULL : new StringType(event.getValue()));
            });
        });
        handlers.put(EVENT_WASHER_IDOS_1_DOSING_LEVEL, event -> {
            getThingChannel(CHANNEL_WASHER_IDOS1).ifPresent(channel -> {
                updateState(channel.getUID(),
                        event.getValue() == null ? UnDefType.NULL : new StringType(event.getValue()));
            });
        });
        handlers.put(EVENT_WASHER_IDOS_2_DOSING_LEVEL, event -> {
            getThingChannel(CHANNEL_WASHER_IDOS2).ifPresent(channel -> {
                updateState(channel.getUID(),
                        event.getValue() == null ? UnDefType.NULL : new StringType(event.getValue()));
            });
        });
    }

    @Override
    public void handleCommand(@NonNull ChannelUID channelUID, @NonNull Command command) {
        if (isThingReadyToHandleCommand()) {
            super.handleCommand(channelUID, command);
            String operationState = getOperationState();

            try {
                // only handle these commands if operation state allows it
                if (operationState != null
                        && (ACTIVE_STATE.contains(operationState) || INACTIVE_STATE.contains(operationState))) {
                    boolean activeState = ACTIVE_STATE.contains(operationState);

                    logger.debugWithHaId(getThingHaId(), "operation state: {} | active: {}", operationState,
                            activeState);

                    // set temperature option
                    if (command instanceof StringType && CHANNEL_WASHER_TEMPERATURE.equals(channelUID.getId())) {
                        getApiClient().setProgramOptions(getThingHaId(), OPTION_WASHER_TEMPERATURE,
                                command.toFullString(), null, false, activeState);
                    }

                    // set spin speed option
                    if (command instanceof StringType && CHANNEL_WASHER_SPIN_SPEED.equals(channelUID.getId())) {
                        getApiClient().setProgramOptions(getThingHaId(), OPTION_WASHER_SPIN_SPEED,
                                command.toFullString(), null, false, activeState);
                    }

                    // set iDos 1 option
                    if (command instanceof StringType && CHANNEL_WASHER_IDOS1.equals(channelUID.getId())) {
                        getApiClient().setProgramOptions(getThingHaId(), OPTION_WASHER_IDOS_1_DOSING_LEVEL,
                                command.toFullString(), null, false, activeState);
                    }

                    // set iDos 2 option
                    if (command instanceof StringType && CHANNEL_WASHER_IDOS2.equals(channelUID.getId())) {
                        getApiClient().setProgramOptions(getThingHaId(), OPTION_WASHER_IDOS_2_DOSING_LEVEL,
                                command.toFullString(), null, false, activeState);
                    }
                }

            } catch (CommunicationException e) {
                logger.warnWithHaId(getThingHaId(), "Could not handle command {}. API communication problem! error: {}",
                        command.toFullString(), e.getMessage());
            } catch (AuthorizationException e) {
                logger.warnWithHaId(getThingHaId(), "Could not handle command {}. Authorization problem! error: {}",
                        command.toFullString(), e.getMessage());

                handleAuthenticationError(e);
            }
        }
    }

    @Override
    public String toString() {
        return "HomeConnectWasherHandler [haId: " + getThingHaId() + "]";
    }

    @Override
    protected void resetProgramStateChannels() {
        super.resetProgramStateChannels();
        getThingChannel(CHANNEL_REMAINING_PROGRAM_TIME_STATE).ifPresent(c -> updateState(c.getUID(), UnDefType.NULL));
        getThingChannel(CHANNEL_PROGRAM_PROGRESS_STATE).ifPresent(c -> updateState(c.getUID(), UnDefType.NULL));
        getThingChannel(CHANNEL_ACTIVE_PROGRAM_STATE).ifPresent(c -> updateState(c.getUID(), UnDefType.NULL));
    }
}

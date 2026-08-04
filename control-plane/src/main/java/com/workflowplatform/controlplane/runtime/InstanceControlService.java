package com.workflowplatform.controlplane.runtime;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Explicit pause/resume/cooperative-cancel lifecycle for workflow instances. */
public final class InstanceControlService {

    private final Map<UUID, ControlState> states = new ConcurrentHashMap<>();

    public void register(UUID instanceId) {
        states.putIfAbsent(instanceId, new ControlState("RUNNING", false, false));
    }

    public synchronized ControlState pause(UUID instanceId) {
        ControlState current = require(instanceId);
        if (!"RUNNING".equals(current.status())) {
            throw new IllegalStateException("only RUNNING instances can be paused");
        }
        ControlState next = new ControlState("PAUSED", true, false);
        states.put(instanceId, next);
        return next;
    }

    public synchronized ControlState resume(UUID instanceId) {
        ControlState current = require(instanceId);
        if (!"PAUSED".equals(current.status())) {
            throw new IllegalStateException("only PAUSED instances can be resumed");
        }
        ControlState next = new ControlState("RUNNING", false, false);
        states.put(instanceId, next);
        return next;
    }

    public synchronized ControlState cancel(UUID instanceId) {
        require(instanceId);
        ControlState next = new ControlState("CANCEL_REQUESTED", true, true);
        states.put(instanceId, next);
        return next;
    }

    private ControlState require(UUID instanceId) {
        ControlState state = states.get(instanceId);
        if (state == null) {
            throw new IllegalArgumentException("instance not found");
        }
        return state;
    }

    public record ControlState(String status, boolean stopNewDispatch, boolean cooperativeCancel) {}
}

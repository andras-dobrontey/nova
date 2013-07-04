package com.dotc.nova.events;

import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dotc.nova.ProcessingLoop;

public class AsyncEventEmitter implements EventEmitter {
	private static final Logger LOGGER = LoggerFactory.getLogger(AsyncEventEmitter.class);

	private final HashMap<Object, List<EventListener>> mapEventToHandler = new HashMap<Object, List<EventListener>>();
	private final HashMap<Object, List<EventListener>> mapEventToOneOffHandlers = new HashMap<Object, List<EventListener>>();
	private final ProcessingLoop eventDispatcher;

	public AsyncEventEmitter(ProcessingLoop eventDispatcher) {
		this.eventDispatcher = eventDispatcher;
	}

	public void on(Object event, EventListener callback) {
		addListener(event, callback);
	}

	public void once(Object event, EventListener callback) {
		addListener(event, callback, mapEventToOneOffHandlers);
	}

	public void addListener(Object event, EventListener callback) {
		addListener(event, callback, mapEventToHandler);
	}

	private void addListener(Object event, EventListener callback, Map<Object, List<EventListener>> listenerMap) {
		if (event == null) {
			throw new IllegalArgumentException("event must not be null");
		}
		if (callback == null) {
			throw new IllegalArgumentException("handler must not be null");
		}
		List<EventListener> handlers = listenerMap.get(event);
		if (handlers == null) {
			handlers = new ArrayList<>();
			listenerMap.put(event, handlers);
			LOGGER.debug("Registered event " + event + " --> " + callback);
		}
		handlers.add(callback);
	}

	public void removeListener(Object event, EventListener handler) {
		if (event == null) {
			throw new IllegalArgumentException("event must not be null");
		}
		if (handler == null) {
			throw new IllegalArgumentException("handler must not be null");
		}
		// remove listener from normal list
		List<EventListener> handlers = mapEventToHandler.get(event);
		if (handlers != null) {
			handlers.remove(handler);
			if (handlers.isEmpty()) {
				mapEventToHandler.remove(event);
			}
			LOGGER.debug("Deregistered handler " + event + " --> " + handler);
		}
		// remove listener from one off list
		handlers = mapEventToOneOffHandlers.get(event);
		if (handlers != null) {
			handlers.remove(handler);
			if (handlers.isEmpty()) {
				mapEventToHandler.remove(event);
			}
			LOGGER.debug("Deregistered one off handler " + event + " --> " + handler);
		}
	}

	public void removeAllListeners(Object event) {
		if (event == null) {
			throw new IllegalArgumentException("event must not be null");
		}
		mapEventToHandler.remove(event);
		LOGGER.debug("Deregistered all listeners for event " + event);
	}

	List<EventListener> getListeners(Object event) {
		if (event == null) {
			throw new IllegalArgumentException("event must not be null");
		}
		List<EventListener> listeners = mapEventToHandler.get(event);
		if (listeners == null) {
			return new ArrayList<>();
		} else {
			return listeners;
		}
	}

	public <EventType, ParameterType> void emit(EventType event, ParameterType... data) {
		if (event == null) {
			throw new IllegalArgumentException("event must not be null");
		}
		List<EventListener> listenerList = new ArrayList<>();
		List<EventListener> normalListenerList = mapEventToHandler.get(event);
		if (normalListenerList != null) {
			listenerList.addAll(normalListenerList);
		}
		List<EventListener> oneOffListeners = mapEventToOneOffHandlers.remove(event);
		if (oneOffListeners != null) {
			listenerList.addAll(oneOffListeners);
		}
		if (!listenerList.isEmpty()) {
			eventDispatcher.dispatch(event, listenerList, data);
		}
	}

}
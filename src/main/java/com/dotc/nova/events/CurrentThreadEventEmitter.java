package com.dotc.nova.events;

import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CurrentThreadEventEmitter extends EventEmitter {
	private static final Logger LOGGER = LoggerFactory.getLogger(CurrentThreadEventEmitter.class);

	public CurrentThreadEventEmitter(boolean warnOnUnhandledEvents) {
		super(warnOnUnhandledEvents);
	}

	@SuppressWarnings("unchecked")
	@Override
	<EventType> void dispatchEventAndDataToListeners(List<EventListener> listenerList, EventType event, Object... data) {
		Object[] dataToPass = data.length == 0 ? null : data;
		for (EventListener listener : listenerList) {
			try {
				listener.handle(dataToPass);
			} catch (Exception e) {
				LOGGER.error("Uncaught exception while invoking eventListener " + listener, e);
				LOGGER.error("\tparamters: " + Arrays.toString(data));
			}
		}
	}

}

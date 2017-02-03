/*
 * Copyright (c) Squaredesk GmbH and Oliver Dotzauer.
 *
 * This program is distributed under the squaredesk open source license. See the LICENSE file
 * distributed with this work for additional information regarding copyright ownership. You may also
 * obtain a copy of the license at
 *
 *   https://squaredesk.ch/license/oss/LICENSE
 */

package ch.squaredesk.nova.timers;

import java.util.concurrent.*;

import ch.squaredesk.nova.events.EventLoop;
import io.reactivex.Emitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Timers {
	private static final Logger LOGGER = LoggerFactory.getLogger(Timers.class);

	private final EventLoop eventLoop;
	private final ScheduledExecutorService executor;
	private long counter = 0;
	private ConcurrentHashMap<String, ScheduledFuture<?>> mapIdToFuture = new ConcurrentHashMap<>();

	public Timers(EventLoop eventLoop) {
		this.eventLoop = eventLoop;

		ThreadFactory tf = runnable -> {
			Thread t = new Thread(runnable, "Timers");
			t.setDaemon(true);
			return t;
		};
		executor = Executors.newSingleThreadScheduledExecutor(tf);
	}

	/**
	 * To schedule execution of a one-time callback after delay milliseconds. Returns a timeoutId for possible use with clearTimeout().
	 *
	 * It is important to note that your callback will probably not be called in exactly delay milliseconds - Nova makes no guarantees about the exact timing of when the callback will fire, nor of the
	 * ordering things will fire in. The callback will be called as close as possible to the time specified.
	 */
	public String setTimeout(Runnable callback, long delay) {
		if (callback == null) {
			throw new IllegalArgumentException("callback must not be null");
		}
		long id = ++counter;
		String idAsString = String.valueOf(id);

		mapIdToFuture.put(idAsString,
				executor.schedule(new TimeoutCallbackWrapper(idAsString, callback), delay, TimeUnit.MILLISECONDS));

		return idAsString;
	}

	public String setTimeout(Runnable callback, long delay, TimeUnit timeUnit) {
		return setTimeout(callback, timeUnit.toMillis(delay));
	}

	/** Prevents the timeout with the passed ID from triggering. */
	public void clearTimeout(String timeoutId) {
		if (timeoutId == null) {
			throw new IllegalArgumentException("timeoutId must not be null");
		}
		ScheduledFuture<?> sf = mapIdToFuture.remove(timeoutId);
		if (sf != null) {
			sf.cancel(false);
		}
	}

	/**
	 * To schedule the repeated execution of callback every delay milliseconds. Returns a intervalId for possible use with clearInterval().
	 *
	 */
	public String setInterval(Runnable callback, long delay) {
		return setInterval(callback, delay, TimeUnit.MILLISECONDS);
	}

	/**
	 * To schedule the repeated execution of callback. Returns a intervalId for possible use with clearInterval().
	 *
	 */
	public String setInterval(Runnable callback, long delay, TimeUnit timeUnit) {
		if (callback == null) {
			throw new IllegalArgumentException("callback must not be null");
		}
		if (timeUnit == null) {
			throw new IllegalArgumentException("timeUnit must not be null");
		}
		long id = ++counter;
		String idAsString = String.valueOf(id);

		mapIdToFuture.put(idAsString,
				executor.scheduleWithFixedDelay(new IntervalCallbackWrapper(callback), delay, delay, timeUnit));

		return idAsString;
	}

	/**
	 * Stops an interval from triggering.
	 */
	public void clearInterval(String intervalId) {
		if (intervalId == null) {
			throw new IllegalArgumentException("timeoutId must not be null");
		}
		ScheduledFuture<?> sf = mapIdToFuture.remove(intervalId);
		if (sf != null) {
			sf.cancel(false);
		}
	}

	private class TimeoutCallbackWrapper implements Runnable {
		private final Emitter<Object[]> handlerToInvoke;

		public TimeoutCallbackWrapper(final String callbackId, final Runnable runnableToInvoke) {
			this.handlerToInvoke = data -> {
				clearTimeout(callbackId);
				runnableToInvoke.run();
			};
		}

		@Override
		public void run() {
			try {
				eventLoop.dispatch(handlerToInvoke);
			} catch (Throwable t) {
				LOGGER.error("Unable to put timeout callback on processing loop", t);
			}
		}

	}

	private class IntervalCallbackWrapper implements Runnable {
		private final Emitter<Object[]> handlerToInvoke;

		public IntervalCallbackWrapper(final Runnable runnableToInvoke) {
			this.handlerToInvoke = data -> runnableToInvoke.run();
		}

		@Override
		public void run() {
			try {
				eventLoop.dispatch(handlerToInvoke);
			} catch (Throwable t) {
				LOGGER.error("Unable to put interval callback on processing loop", t);
			}
		}

	}

    private class EmittingWrapper implements Emitter<Object[]> {
	    private final Runnable callback;

        private EmittingWrapper(Runnable callback) {
            this.callback = callback;
        }

        @Override
        public void onNext(Object[] value) {
            callback.run();;
        }
        @Override
        public void onError(Throwable error) {
        }
        @Override
        public void onComplete() {
        }
    };

}

/*
 * Copyright 2002-2008 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.bus;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.Lifecycle;
import org.springframework.integration.ConfigurationException;
import org.springframework.integration.adapter.SourceAdapter;
import org.springframework.integration.channel.ChannelRegistry;
import org.springframework.integration.channel.ChannelRegistryAware;
import org.springframework.integration.channel.DefaultChannelRegistry;
import org.springframework.integration.channel.MessageChannel;
import org.springframework.integration.channel.SimpleChannel;
import org.springframework.integration.dispatcher.DefaultMessageDispatcher;
import org.springframework.integration.dispatcher.SchedulingMessageDispatcher;
import org.springframework.integration.dispatcher.SynchronousChannel;
import org.springframework.integration.endpoint.ConcurrencyPolicy;
import org.springframework.integration.endpoint.DefaultEndpointRegistry;
import org.springframework.integration.endpoint.EndpointRegistry;
import org.springframework.integration.endpoint.HandlerEndpoint;
import org.springframework.integration.endpoint.MessageEndpoint;
import org.springframework.integration.endpoint.TargetEndpoint;
import org.springframework.integration.handler.MessageHandler;
import org.springframework.integration.message.MessagingException;
import org.springframework.integration.message.Target;
import org.springframework.integration.scheduling.MessagePublishingErrorHandler;
import org.springframework.integration.scheduling.MessagingTask;
import org.springframework.integration.scheduling.MessagingTaskScheduler;
import org.springframework.integration.scheduling.Schedule;
import org.springframework.integration.scheduling.SimpleMessagingTaskScheduler;
import org.springframework.integration.scheduling.Subscription;
import org.springframework.integration.util.ErrorHandler;
import org.springframework.util.Assert;

/**
 * The messaging bus. Serves as a registry for channels and endpoints, manages their lifecycle,
 * and activates subscriptions.
 * 
 * @author Mark Fisher
 */
public class MessageBus implements ChannelRegistry, EndpointRegistry, ApplicationContextAware, Lifecycle {

	public static final String ERROR_CHANNEL_NAME = "errorChannel";

	private static final int DEFAULT_DISPATCHER_POOL_SIZE = 10;


	private final Log logger = LogFactory.getLog(this.getClass());

	private final ChannelRegistry channelRegistry = new DefaultChannelRegistry();

	private final EndpointRegistry endpointRegistry = new DefaultEndpointRegistry();

	private final Map<MessageChannel, SchedulingMessageDispatcher> dispatchers = new ConcurrentHashMap<MessageChannel, SchedulingMessageDispatcher>();

	private final List<Lifecycle> lifecycleSourceAdapters = new CopyOnWriteArrayList<Lifecycle>();

	private volatile MessagingTaskScheduler taskScheduler;

	private volatile ScheduledExecutorService executor;

	private volatile ConcurrencyPolicy defaultConcurrencyPolicy;

	private volatile boolean autoCreateChannels;

	private volatile boolean autoStartup = true;

	private volatile boolean initialized;

	private volatile boolean initializing;

	private volatile boolean starting;

	private volatile boolean running;

	private final Object lifecycleMonitor = new Object();


	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		Assert.notNull(applicationContext, "'applicationContext' must not be null");
		if (applicationContext.getBeanNamesForType(this.getClass()).length > 1) {
			throw new ConfigurationException("Only one instance of '" + this.getClass().getSimpleName()
					+ "' is allowed per ApplicationContext.");
		}
		this.registerChannels(applicationContext);
		this.registerEndpoints(applicationContext);
		this.registerSourceAdapters(applicationContext);
		if (this.autoStartup) {
			this.start();
		}
	}

	/**
	 * Set the {@link ScheduledExecutorService} to use for scheduling message dispatchers.
	 */
	public void setScheduledExecutorService(ScheduledExecutorService executor) {
		this.executor = executor;
	}

	/**
	 * Specify the default concurrency policy to be used for any endpoint that
	 * is registered without an explicitly provided policy of its own.
	 */
	public void setDefaultConcurrencyPolicy(ConcurrencyPolicy defaultConcurrencyPolicy) {
		this.defaultConcurrencyPolicy = defaultConcurrencyPolicy;
	}

	/**
	 * Set whether to automatically start the bus after initialization.
	 * <p>Default is 'true'; set this to 'false' to allow for manual startup
	 * through the {@link #start()} method.
	 */
	public void setAutoStartup(boolean autoStartup) {
		this.autoStartup = autoStartup;
	}

	/**
	 * Set whether the bus should automatically create a channel when a
	 * subscription contains the name of a previously unregistered channel.
	 */
	public void setAutoCreateChannels(boolean autoCreateChannels) {
		this.autoCreateChannels = autoCreateChannels;
	}

	@SuppressWarnings("unchecked")
	private void registerChannels(ApplicationContext context) {
		Map<String, MessageChannel> channelBeans =
				(Map<String, MessageChannel>) context.getBeansOfType(MessageChannel.class);
		for (Map.Entry<String, MessageChannel> entry : channelBeans.entrySet()) {
			this.registerChannel(entry.getKey(), entry.getValue());
		}
	}

	@SuppressWarnings("unchecked")
	private void registerEndpoints(ApplicationContext context) {
		Map<String, MessageEndpoint> endpointBeans =
				(Map<String, MessageEndpoint>) context.getBeansOfType(MessageEndpoint.class);
		for (Map.Entry<String, MessageEndpoint> entry : endpointBeans.entrySet()) {
			this.registerEndpoint(entry.getKey(), entry.getValue());
		}
	}

	@SuppressWarnings("unchecked")
	private void registerSourceAdapters(ApplicationContext context) {
		Map<String, SourceAdapter> sourceAdapterBeans =
				(Map<String, SourceAdapter>) context.getBeansOfType(SourceAdapter.class);
		for (Map.Entry<String, SourceAdapter> entry : sourceAdapterBeans.entrySet()) {
			this.registerSourceAdapter(entry.getKey(), entry.getValue());
		}
	}

	public void initialize() {
		synchronized (this.lifecycleMonitor) {
			if (this.initialized || this.initializing) {
				return;
			}
			this.initializing = true;
			if (this.executor == null) {
				this.executor = new ScheduledThreadPoolExecutor(DEFAULT_DISPATCHER_POOL_SIZE);
			}
			this.taskScheduler = new SimpleMessagingTaskScheduler(this.executor);
			if (this.getErrorChannel() == null) {
				this.setErrorChannel(new DefaultErrorChannel());
			}
			this.taskScheduler.setErrorHandler(new MessagePublishingErrorHandler(this.getErrorChannel()));
			this.initialized = true;
			this.initializing = false;
		}
	}

	public MessageChannel getErrorChannel() {
		return this.lookupChannel(ERROR_CHANNEL_NAME);
	}

	public void setErrorChannel(MessageChannel errorChannel) {
		this.registerChannel(ERROR_CHANNEL_NAME, errorChannel);
	}

	public MessageChannel lookupChannel(String channelName) {
		return this.channelRegistry.lookupChannel(channelName);
	}

	public void registerChannel(String name, MessageChannel channel) {
		if (!this.initialized) {
			this.initialize();
		}
		channel.setName(name);
		DefaultMessageDispatcher dispatcher = new DefaultMessageDispatcher(channel, this.taskScheduler);
		this.dispatchers.put(channel, dispatcher);
		this.channelRegistry.registerChannel(name, channel);
		if (logger.isInfoEnabled()) {
			logger.info("registered channel '" + name + "'");
		}
	}

	public MessageChannel unregisterChannel(String name) {
		MessageChannel removedChannel = this.channelRegistry.unregisterChannel(name);
		if (removedChannel != null) {
			SchedulingMessageDispatcher removedDispatcher = this.dispatchers.remove(removedChannel);
			if (removedDispatcher != null && removedDispatcher.isRunning()) {
				removedDispatcher.stop();
			}
		}
		return removedChannel;
	}

	public void registerHandler(String name, MessageHandler handler, Subscription subscription) {
		this.registerHandler(name, handler, subscription, this.defaultConcurrencyPolicy);
	}

	public void registerHandler(String name, MessageHandler handler, Subscription subscription, ConcurrencyPolicy concurrencyPolicy) {
		Assert.notNull(handler, "'handler' must not be null");
		HandlerEndpoint endpoint = new HandlerEndpoint(handler);
		this.doRegisterEndpoint(name, endpoint, subscription, concurrencyPolicy);
	}

	public void registerTarget(String name, Target target, Subscription subscription) {
		this.registerTarget(name, target, subscription, this.defaultConcurrencyPolicy);
	}

	public void registerTarget(String name, Target target, Subscription subscription, ConcurrencyPolicy concurrencyPolicy) {
		Assert.notNull(target, "'target' must not be null");
		TargetEndpoint endpoint = new TargetEndpoint(target);
		this.doRegisterEndpoint(name, endpoint, subscription, concurrencyPolicy);
	}

	private void doRegisterEndpoint(String name, TargetEndpoint endpoint, Subscription subscription, ConcurrencyPolicy concurrencyPolicy) {
		endpoint.setName(name);
		endpoint.setSubscription(subscription);
		endpoint.setConcurrencyPolicy(concurrencyPolicy);
		this.registerEndpoint(name, endpoint);
	}

	public void registerEndpoint(String name, MessageEndpoint endpoint) {
		if (!this.initialized) {
			this.initialize();
		}
		if (endpoint instanceof ChannelRegistryAware) {
			((ChannelRegistryAware) endpoint).setChannelRegistry(this.channelRegistry);
		}
		if (endpoint.getConcurrencyPolicy() == null && this.defaultConcurrencyPolicy != null
				&& endpoint instanceof TargetEndpoint) {
			((TargetEndpoint) endpoint).setConcurrencyPolicy(this.defaultConcurrencyPolicy);
		}
		if (endpoint instanceof TargetEndpoint) {
			((TargetEndpoint) endpoint).afterPropertiesSet();
		}
		this.endpointRegistry.registerEndpoint(name, endpoint);
		if (this.isRunning()) {
			activateEndpoint(endpoint);
		}
		if (logger.isInfoEnabled()) {
			logger.info("registered endpoint '" + name + "'");
		}
	}

	public MessageEndpoint unregisterEndpoint(String name) {
		MessageEndpoint endpoint = this.endpointRegistry.unregisterEndpoint(name);
		if (endpoint == null) {
			return null;
		}
		Collection<SchedulingMessageDispatcher> dispatchers = this.dispatchers.values();
		boolean removed = false;
		for (SchedulingMessageDispatcher dispatcher : dispatchers) {
			removed = (removed || dispatcher.removeTarget(endpoint));
		}
		if (removed) {
			return endpoint;
		}
		return null;
	}

	public MessageEndpoint lookupEndpoint(String endpointName) {
		return this.endpointRegistry.lookupEndpoint(endpointName);
	}

	public Set<String> getEndpointNames() {
		return this.endpointRegistry.getEndpointNames();
	}

	private void activateEndpoints() {
		Set<String> endpointNames = this.endpointRegistry.getEndpointNames();
		for (String name : endpointNames) {
			MessageEndpoint endpoint = this.endpointRegistry.lookupEndpoint(name);
			if (endpoint != null) {
				this.activateEndpoint(endpoint);
			}
		}
	}

	private void activateEndpoint(MessageEndpoint endpoint) {
		Subscription subscription = endpoint.getSubscription();
		if (subscription == null) {
			throw new ConfigurationException("Unable to register endpoint '" +
					endpoint + "'. No subscription information is available.");
		}
		MessageChannel channel = subscription.getChannel();
		if (channel == null) {
			String channelName = subscription.getChannelName();
			if (channelName == null) {
				throw new ConfigurationException("endpoint '" + endpoint +
						"' must provide either 'channel' or 'channelName' in its subscription metadata");
			}
			channel = this.lookupChannel(channelName);
			if (channel == null) {
				if (this.autoCreateChannels == false) {
					throw new ConfigurationException("Cannot activate subscription, unknown channel '" + channelName +
							"'. Consider enabling the 'autoCreateChannels' option for the message bus.");
				}
				if (this.logger.isInfoEnabled()) {
					logger.info("auto-creating channel '" + channelName + "'");
				}
				channel = new SimpleChannel(); 
				this.registerChannel(channelName, channel);
			}
		}
		if (endpoint instanceof HandlerEndpoint) {
			HandlerEndpoint handlerEndpoint = (HandlerEndpoint) endpoint;
			String outputChannelName = handlerEndpoint.getDefaultOutputChannelName();
			if (outputChannelName != null && this.lookupChannel(outputChannelName) == null) {
				if (!this.autoCreateChannels) {
					throw new ConfigurationException("Unknown channel '" + outputChannelName +
							"' configured as 'default-output' for endpoint '" + endpoint +
							"'. Consider enabling the 'autoCreateChannels' option for the message bus.");
				}
				this.registerChannel(outputChannelName, new SimpleChannel());
			}
		}
		if (endpoint instanceof TargetEndpoint) {
			TargetEndpoint targetEndpoint = (TargetEndpoint) endpoint;
			if (!targetEndpoint.hasErrorHandler() && this.getErrorChannel() != null && !this.getErrorChannel().equals(channel)) {
				targetEndpoint.setErrorHandler(new MessagePublishingErrorHandler(this.getErrorChannel()));
			}
		}
		this.registerWithDispatcher(channel, endpoint, subscription.getSchedule());
		if (logger.isInfoEnabled()) {
			logger.info("activated subscription to channel '" + channel.getName() + 
					"' for endpoint '" + endpoint + "'");
		}
	}

	public void registerSourceAdapter(String name, SourceAdapter adapter) {
		if (!this.initialized) {
			this.initialize();
		}
		if (adapter instanceof MessagingTask) {
			this.taskScheduler.schedule((MessagingTask) adapter);
		}
		if (adapter instanceof Lifecycle) {
			this.lifecycleSourceAdapters.add((Lifecycle) adapter);
			if (this.isRunning()) {
				((Lifecycle) adapter).start();
			}
		}
		if (logger.isInfoEnabled()) {
			logger.info("registered source adapter '" + name + "'");
		}
	}

	private void registerWithDispatcher(MessageChannel channel, Target target, Schedule schedule) {
		if (schedule == null && (channel instanceof SynchronousChannel)) {
			((SynchronousChannel) channel).addTarget(target);
			if (target instanceof Lifecycle) {
				((Lifecycle) target).start();
			}
			if (target instanceof TargetEndpoint) {
				((TargetEndpoint) target).setErrorHandler(new ErrorHandler() {
					public void handle(Throwable t) {
						if (t instanceof MessagingException) {
							throw (MessagingException) t;
						}
						throw new MessagingException("error occurred in handler", t);
					}
				});
			}
			return;
		}
		SchedulingMessageDispatcher dispatcher = dispatchers.get(channel);
		if (dispatcher == null) {
			if (logger.isWarnEnabled()) {
				logger.warn("no dispatcher available for channel '" + channel.getName() + "', be sure to register the channel");
			}
			return;
		}
		dispatcher.addTarget(target, schedule);
		if (this.isRunning() && !dispatcher.isRunning()) {
			dispatcher.start();
		}
	}

	public boolean isRunning() {
		synchronized (this.lifecycleMonitor) {
			return this.running;
		}
	}

	public void start() {
		if (!this.initialized) {
			this.initialize();
		}
		if (this.isRunning() || this.starting) {
			return;
		}
		this.starting = true;
		synchronized (this.lifecycleMonitor) {
			this.activateEndpoints();
			this.taskScheduler.start();
			for (SchedulingMessageDispatcher dispatcher : this.dispatchers.values()) {
				dispatcher.start();
				if (logger.isInfoEnabled()) {
					logger.info("started dispatcher '" + dispatcher + "'");
				}
			}
			for (Lifecycle adapter : this.lifecycleSourceAdapters) {
				adapter.start();
				if (logger.isInfoEnabled()) {
					logger.info("started source adapter '" + adapter + "'");
				}
			}
		}
		this.running = true;
		this.starting = false;
		if (logger.isInfoEnabled()) {
			logger.info("message bus started");
		}
	}

	public void stop() {
		if (!this.isRunning()) {
			return;
		}
		synchronized (this.lifecycleMonitor) {
			this.running = false;
			this.taskScheduler.stop();
			for (Lifecycle adapter : this.lifecycleSourceAdapters) {
				adapter.stop();
				if (logger.isInfoEnabled()) {
					logger.info("stopped source adapter '" + adapter + "'");
				}
			}
			for (SchedulingMessageDispatcher dispatcher : this.dispatchers.values()) {
				dispatcher.stop();
				if (logger.isInfoEnabled()) {
					logger.info("stopped dispatcher '" + dispatcher + "'");
				}
			}
		}
		if (logger.isInfoEnabled()) {
			logger.info("message bus stopped");
		}
	}

}

/*
 * Copyright 2015 the original author or authors.
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
package com.heliosapm.streams.collector.jmx;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.InstanceAlreadyExistsException;
import javax.management.InstanceNotFoundException;
import javax.management.IntrospectionException;
import javax.management.InvalidAttributeValueException;
import javax.management.ListenerNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServerConnection;
import javax.management.NotCompliantMBeanException;
import javax.management.NotificationFilter;
import javax.management.NotificationListener;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.QueryExp;
import javax.management.ReflectionException;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cliffc.high_scale_lib.NonBlockingHashSet;

import com.heliosapm.streams.collector.TimeoutService;
import com.heliosapm.utils.jmx.JMXHelper;
import com.heliosapm.utils.lang.StringHelper;

import io.netty.util.Timeout;

/**
 * <p>Title: JMXClient</p>
 * <p>Description: A managed JMX client for data collection</p> 
 * <p>Company: Helios Development Group LLC</p>
 * @author Whitehead (nwhitehead AT heliosdev DOT org)
 * <p><code>com.heliosapm.streams.collector.jmx.JMXClient</code></p>
 */

public class JMXClient {
	/** The JMX URL */
	protected final String jmxUrl;
	/** The JMX Service URL */
	protected final JMXServiceURL jmxServiceUrl;
	/** The JMXConnector */
	protected final JMXConnector jmxConnector;
	/** The JMXConnector env */
	protected final Map<String, Object> env = new HashMap<String, Object>();
	/** The mbean server connection acquired from the JMXConnector */
	protected volatile MBeanServerConnection server = null;
	/** The timeout on connecting and acquiring a connection */
	protected final long connectTimeoutSecs;
	/** Instance logger */
	protected final Logger log;
	/** Listener registrations that should be saved an re-applied on re-connect */
	protected final NonBlockingHashSet<SavedNotificationEvent> registrations = new NonBlockingHashSet<SavedNotificationEvent>();
	
	public static JMXClient newInstance(final CharSequence cs) {
		if(cs==null) throw new IllegalArgumentException("The passed char sequence was null");
		final String str = cs.toString().trim();
		if(str.isEmpty()) throw new IllegalArgumentException("The passed char sequence was empty");
		final String[] frags = StringHelper.splitString(str, ',', true);
	}
	
	/**
	 * Creates a new JMXClient
	 * @param jmxUrl The JMX URL
	 * @param connectTimeoutSecs The timeout on connecting and acquiring a connection
	 * @param credentials The optional credentials
	 */
	public JMXClient(final String jmxUrl, final long connectTimeoutSecs, final String...credentials) {
		super();
		this.jmxUrl = jmxUrl;
		this.connectTimeoutSecs = connectTimeoutSecs;
		jmxServiceUrl = JMXHelper.serviceUrl(jmxUrl);
		log = LogManager.getLogger(getClass().getName() + "-" + jmxServiceUrl.getHost().replace('.', '_') + "-" + jmxServiceUrl.getPort());
		if(credentials!=null && credentials.length > 1) {
			final String[] creds = new String[2];
			System.arraycopy(credentials, 0, creds, 0, 2);
			env.put(JMXConnector.CREDENTIALS, creds);
		}
		
		try {
			jmxConnector = JMXConnectorFactory.newJMXConnector(jmxServiceUrl, env);
		} catch (IOException iex) {
			throw new RuntimeException("Failed to create JMXConnector", iex);
		}
	}
	
	/**
	 * Acquires the MBeanServerConnection server
	 * @return the MBeanServerConnection server
	 */
	public MBeanServerConnection server() {
		if(server==null) {
			synchronized(this) {
				if(server==null) {
					try {
						final Timeout txout = TimeoutService.getInstance().timeout(connectTimeoutSecs, TimeUnit.SECONDS, new Runnable(){
							final Thread me = Thread.currentThread();
							@Override
							public void run() {
								log.warn("Connect Timeout !!!\n{}", StringHelper.formatStackTrace(me));
								me.interrupt();
								log.warn("JMXConnector interrupted after timeout");
							}
						});
						jmxConnector.connect();
						server = jmxConnector.getMBeanServerConnection();
						txout.cancel();
						for(SavedNotificationEvent n: registrations) {							
							try {
								if(n.listener!=null) {
									server.addNotificationListener(n.name, n.listener, n.filter, n.handback);
								} else {
									server.addNotificationListener(n.name, n.listenerObjectName, n.filter, n.handback);
								}
							} catch (Exception ex) {
								registrations.remove(n);
								log.error("Failed to apply notif registration [" + n + "]", ex);
							}
						}
					} catch (Exception ex) {
						throw new RuntimeException("Failed to get MBeanServerConnection for [" + jmxServiceUrl + "]");
					}
				}
			}
		}
		return server;
	}

	/**
	 * @param className
	 * @param name
	 * @return
	 * @throws ReflectionException
	 * @throws InstanceAlreadyExistsException
	 * @throws MBeanRegistrationException
	 * @throws MBeanException
	 * @throws NotCompliantMBeanException
	 * @throws IOException
	 * @see javax.management.MBeanServerConnection#createMBean(java.lang.String, javax.management.ObjectName)
	 */
	public ObjectInstance createMBean(String className, ObjectName name)
			throws ReflectionException, InstanceAlreadyExistsException, MBeanRegistrationException, MBeanException,
			NotCompliantMBeanException, IOException {
		return server().createMBean(className, name);
	}

	/**
	 * @param className
	 * @param name
	 * @param loaderName
	 * @return
	 * @throws ReflectionException
	 * @throws InstanceAlreadyExistsException
	 * @throws MBeanRegistrationException
	 * @throws MBeanException
	 * @throws NotCompliantMBeanException
	 * @throws InstanceNotFoundException
	 * @throws IOException
	 * @see javax.management.MBeanServerConnection#createMBean(java.lang.String, javax.management.ObjectName, javax.management.ObjectName)
	 */
	public ObjectInstance createMBean(String className, ObjectName name, ObjectName loaderName)
			throws ReflectionException, InstanceAlreadyExistsException, MBeanRegistrationException, MBeanException,
			NotCompliantMBeanException, InstanceNotFoundException, IOException {
		return server().createMBean(className, name, loaderName);
	}

	/**
	 * @param className
	 * @param name
	 * @param params
	 * @param signature
	 * @return
	 * @throws ReflectionException
	 * @throws InstanceAlreadyExistsException
	 * @throws MBeanRegistrationException
	 * @throws MBeanException
	 * @throws NotCompliantMBeanException
	 * @throws IOException
	 * @see javax.management.MBeanServerConnection#createMBean(java.lang.String, javax.management.ObjectName, java.lang.Object[], java.lang.String[])
	 */
	public ObjectInstance createMBean(String className, ObjectName name, Object[] params, String[] signature)
			throws ReflectionException, InstanceAlreadyExistsException, MBeanRegistrationException, MBeanException,
			NotCompliantMBeanException, IOException {
		return server().createMBean(className, name, params, signature);
	}

	/**
	 * @param className
	 * @param name
	 * @param loaderName
	 * @param params
	 * @param signature
	 * @return
	 * @throws ReflectionException
	 * @throws InstanceAlreadyExistsException
	 * @throws MBeanRegistrationException
	 * @throws MBeanException
	 * @throws NotCompliantMBeanException
	 * @throws InstanceNotFoundException
	 * @throws IOException
	 * @see javax.management.MBeanServerConnection#createMBean(java.lang.String, javax.management.ObjectName, javax.management.ObjectName, java.lang.Object[], java.lang.String[])
	 */
	public ObjectInstance createMBean(String className, ObjectName name, ObjectName loaderName, Object[] params,
			String[] signature) throws ReflectionException, InstanceAlreadyExistsException, MBeanRegistrationException,
			MBeanException, NotCompliantMBeanException, InstanceNotFoundException, IOException {
		return server().createMBean(className, name, loaderName, params, signature);
	}

	/**
	 * @param name
	 * @throws InstanceNotFoundException
	 * @throws MBeanRegistrationException
	 * @throws IOException
	 * @see javax.management.MBeanServerConnection#unregisterMBean(javax.management.ObjectName)
	 */
	public void unregisterMBean(ObjectName name)
			throws InstanceNotFoundException, MBeanRegistrationException, IOException {
		server().unregisterMBean(name);
	}

	/**
	 * @param name
	 * @return
	 * @throws InstanceNotFoundException
	 * @throws IOException
	 * @see javax.management.MBeanServerConnection#getObjectInstance(javax.management.ObjectName)
	 */
	public ObjectInstance getObjectInstance(ObjectName name) throws InstanceNotFoundException, IOException {
		return server().getObjectInstance(name);
	}

	/**
	 * @param name
	 * @param query
	 * @return
	 * @throws IOException
	 * @see javax.management.MBeanServerConnection#queryMBeans(javax.management.ObjectName, javax.management.QueryExp)
	 */
	public Set<ObjectInstance> queryMBeans(ObjectName name, QueryExp query) throws IOException {
		return server().queryMBeans(name, query);
	}

	/**
	 * @param name
	 * @param query
	 * @return
	 * @throws IOException
	 * @see javax.management.MBeanServerConnection#queryNames(javax.management.ObjectName, javax.management.QueryExp)
	 */
	public Set<ObjectName> queryNames(ObjectName name, QueryExp query) throws IOException {
		return server().queryNames(name, query);
	}

	/**
	 * @param name
	 * @return
	 * @throws IOException
	 * @see javax.management.MBeanServerConnection#isRegistered(javax.management.ObjectName)
	 */
	public boolean isRegistered(ObjectName name) throws IOException {
		return server().isRegistered(name);
	}

	/**
	 * @return
	 * @throws IOException
	 * @see javax.management.MBeanServerConnection#getMBeanCount()
	 */
	public Integer getMBeanCount() throws IOException {
		return server().getMBeanCount();
	}

	/**
	 * @param name
	 * @param attribute
	 * @return
	 * @throws MBeanException
	 * @throws AttributeNotFoundException
	 * @throws InstanceNotFoundException
	 * @throws ReflectionException
	 * @throws IOException
	 * @see javax.management.MBeanServerConnection#getAttribute(javax.management.ObjectName, java.lang.String)
	 */
	public Object getAttribute(ObjectName name, String attribute) throws MBeanException, AttributeNotFoundException,
			InstanceNotFoundException, ReflectionException, IOException {
		return server().getAttribute(name, attribute);
	}

	/**
	 * @param name
	 * @param attributes
	 * @return
	 * @throws InstanceNotFoundException
	 * @throws ReflectionException
	 * @throws IOException
	 * @see javax.management.MBeanServerConnection#getAttributes(javax.management.ObjectName, java.lang.String[])
	 */
	public AttributeList getAttributes(ObjectName name, String[] attributes)
			throws InstanceNotFoundException, ReflectionException, IOException {
		return server().getAttributes(name, attributes);
	}

	/**
	 * @param name
	 * @param attribute
	 * @throws InstanceNotFoundException
	 * @throws AttributeNotFoundException
	 * @throws InvalidAttributeValueException
	 * @throws MBeanException
	 * @throws ReflectionException
	 * @throws IOException
	 * @see javax.management.MBeanServerConnection#setAttribute(javax.management.ObjectName, javax.management.Attribute)
	 */
	public void setAttribute(ObjectName name, Attribute attribute)
			throws InstanceNotFoundException, AttributeNotFoundException, InvalidAttributeValueException,
			MBeanException, ReflectionException, IOException {
		server().setAttribute(name, attribute);
	}

	/**
	 * @param name
	 * @param attributes
	 * @return
	 * @throws InstanceNotFoundException
	 * @throws ReflectionException
	 * @throws IOException
	 * @see javax.management.MBeanServerConnection#setAttributes(javax.management.ObjectName, javax.management.AttributeList)
	 */
	public AttributeList setAttributes(ObjectName name, AttributeList attributes)
			throws InstanceNotFoundException, ReflectionException, IOException {
		return server().setAttributes(name, attributes);
	}

	/**
	 * @param name
	 * @param operationName
	 * @param params
	 * @param signature
	 * @return
	 * @throws InstanceNotFoundException
	 * @throws MBeanException
	 * @throws ReflectionException
	 * @throws IOException
	 * @see javax.management.MBeanServerConnection#invoke(javax.management.ObjectName, java.lang.String, java.lang.Object[], java.lang.String[])
	 */
	public Object invoke(ObjectName name, String operationName, Object[] params, String[] signature)
			throws InstanceNotFoundException, MBeanException, ReflectionException, IOException {
		return server().invoke(name, operationName, params, signature);
	}

	/**
	 * @return
	 * @throws IOException
	 * @see javax.management.MBeanServerConnection#getDefaultDomain()
	 */
	public String getDefaultDomain() throws IOException {
		return server().getDefaultDomain();
	}

	/**
	 * @return
	 * @throws IOException
	 * @see javax.management.MBeanServerConnection#getDomains()
	 */
	public String[] getDomains() throws IOException {
		return server().getDomains();
	}

	/**
	 * @param name
	 * @param listener
	 * @param filter
	 * @param handback
	 * @throws InstanceNotFoundException
	 * @throws IOException
	 * @see javax.management.MBeanServerConnection#addNotificationListener(javax.management.ObjectName, javax.management.NotificationListener, javax.management.NotificationFilter, java.lang.Object)
	 */
	public void addNotificationListener(ObjectName name, NotificationListener listener, NotificationFilter filter, Object handback) throws InstanceNotFoundException, IOException {
		server().addNotificationListener(name, listener, filter, handback);
		registrations.add(new SavedNotificationEvent(name, listener, filter, handback));
	}

	/**
	 * @param name
	 * @param listener
	 * @param filter
	 * @param handback
	 * @throws InstanceNotFoundException
	 * @throws IOException
	 * @see javax.management.MBeanServerConnection#addNotificationListener(javax.management.ObjectName, javax.management.ObjectName, javax.management.NotificationFilter, java.lang.Object)
	 */
	public void addNotificationListener(ObjectName name, ObjectName listener, NotificationFilter filter,
			Object handback) throws InstanceNotFoundException, IOException {
		server().addNotificationListener(name, listener, filter, handback);
		registrations.add(new SavedNotificationEvent(name, listener, filter, handback));
	}

	/**
	 * @param name
	 * @param listener
	 * @throws InstanceNotFoundException
	 * @throws ListenerNotFoundException
	 * @throws IOException
	 * @see javax.management.MBeanServerConnection#removeNotificationListener(javax.management.ObjectName, javax.management.ObjectName)
	 */
	public void removeNotificationListener(ObjectName name, ObjectName listener)
			throws InstanceNotFoundException, ListenerNotFoundException, IOException {
		server().removeNotificationListener(name, listener);
	}

	/**
	 * @param name
	 * @param listener
	 * @param filter
	 * @param handback
	 * @throws InstanceNotFoundException
	 * @throws ListenerNotFoundException
	 * @throws IOException
	 * @see javax.management.MBeanServerConnection#removeNotificationListener(javax.management.ObjectName, javax.management.ObjectName, javax.management.NotificationFilter, java.lang.Object)
	 */
	public void removeNotificationListener(ObjectName name, ObjectName listener, NotificationFilter filter,
			Object handback) throws InstanceNotFoundException, ListenerNotFoundException, IOException {
		server().removeNotificationListener(name, listener, filter, handback);
	}

	/**
	 * @param name
	 * @param listener
	 * @throws InstanceNotFoundException
	 * @throws ListenerNotFoundException
	 * @throws IOException
	 * @see javax.management.MBeanServerConnection#removeNotificationListener(javax.management.ObjectName, javax.management.NotificationListener)
	 */
	public void removeNotificationListener(ObjectName name, NotificationListener listener)
			throws InstanceNotFoundException, ListenerNotFoundException, IOException {
		server().removeNotificationListener(name, listener);
	}

	/**
	 * @param name
	 * @param listener
	 * @param filter
	 * @param handback
	 * @throws InstanceNotFoundException
	 * @throws ListenerNotFoundException
	 * @throws IOException
	 * @see javax.management.MBeanServerConnection#removeNotificationListener(javax.management.ObjectName, javax.management.NotificationListener, javax.management.NotificationFilter, java.lang.Object)
	 */
	public void removeNotificationListener(ObjectName name, NotificationListener listener, NotificationFilter filter,
			Object handback) throws InstanceNotFoundException, ListenerNotFoundException, IOException {
		server().removeNotificationListener(name, listener, filter, handback);
	}

	/**
	 * @param name
	 * @return
	 * @throws InstanceNotFoundException
	 * @throws IntrospectionException
	 * @throws ReflectionException
	 * @throws IOException
	 * @see javax.management.MBeanServerConnection#getMBeanInfo(javax.management.ObjectName)
	 */
	public MBeanInfo getMBeanInfo(ObjectName name)
			throws InstanceNotFoundException, IntrospectionException, ReflectionException, IOException {
		return server().getMBeanInfo(name);
	}

	/**
	 * @param name
	 * @param className
	 * @return
	 * @throws InstanceNotFoundException
	 * @throws IOException
	 * @see javax.management.MBeanServerConnection#isInstanceOf(javax.management.ObjectName, java.lang.String)
	 */
	public boolean isInstanceOf(ObjectName name, String className) throws InstanceNotFoundException, IOException {
		return server().isInstanceOf(name, className);
	}

	/**
	 * @param listener
	 * @param filter
	 * @param handback
	 * @see javax.management.remote.JMXConnector#addConnectionNotificationListener(javax.management.NotificationListener, javax.management.NotificationFilter, java.lang.Object)
	 */
	public void addConnectionNotificationListener(NotificationListener listener, NotificationFilter filter,
			Object handback) {
		jmxConnector.addConnectionNotificationListener(listener, filter, handback);
	}

	/**
	 * @param listener
	 * @throws ListenerNotFoundException
	 * @see javax.management.remote.JMXConnector#removeConnectionNotificationListener(javax.management.NotificationListener)
	 */
	public void removeConnectionNotificationListener(NotificationListener listener) throws ListenerNotFoundException {
		jmxConnector.removeConnectionNotificationListener(listener);
	}

	/**
	 * @param l
	 * @param f
	 * @param handback
	 * @throws ListenerNotFoundException
	 * @see javax.management.remote.JMXConnector#removeConnectionNotificationListener(javax.management.NotificationListener, javax.management.NotificationFilter, java.lang.Object)
	 */
	public void removeConnectionNotificationListener(NotificationListener l, NotificationFilter f, Object handback)
			throws ListenerNotFoundException {
		jmxConnector.removeConnectionNotificationListener(l, f, handback);
	}

	/**
	 * @return
	 * @throws IOException
	 * @see javax.management.remote.JMXConnector#getConnectionId()
	 */
	public String getConnectionId() throws IOException {
		return jmxConnector.getConnectionId();
	}
	
	
	private class SavedNotificationEvent {
		/** The object name to register the listener on */
		private final ObjectName name;
		/** The listener */		
		private final NotificationListener listener;
		/** The object name listener */		
		private final ObjectName listenerObjectName;

		/** The filter */
		private final NotificationFilter filter;
		/** The handback */
		private final Object handback;
		
		
		/**
		 * Creates a new SavedNotificationEvent
		 * @param name The object name to register the listener on 
		 * @param listener The listener
		 * @param filter The filter
		 * @param handback the handback
		 */
		public SavedNotificationEvent(final ObjectName name, final NotificationListener listener, final NotificationFilter filter, final Object handback) {
			this.name = name;
			this.listener = listener;
			this.filter = filter;
			this.handback = handback;
			this.listenerObjectName = null;
		}
		
		/**
		 * Creates a new SavedNotificationEvent
		 * @param name The object name to register the listener on 
		 * @param listener The object name of the listener
		 * @param filter The filter
		 * @param handback the handback
		 */
		public SavedNotificationEvent(final ObjectName name, final ObjectName listener, final NotificationFilter filter, final Object handback) {
			this.name = name;
			this.listenerObjectName = listener;
			this.filter = filter;
			this.handback = handback;
			this.listener = null;
		}

		/**
		 * {@inheritDoc}
		 * @see java.lang.Object#hashCode()
		 */
		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + getOuterType().hashCode();
			result = prime * result + ((filter == null) ? 0 : filter.hashCode());
			result = prime * result + ((handback == null) ? 0 : handback.hashCode());
			result = prime * result + ((listener == null) ? 0 : listener.hashCode());
			result = prime * result + ((listenerObjectName == null) ? 0 : listenerObjectName.hashCode());
			result = prime * result + ((name == null) ? 0 : name.hashCode());
			return result;
		}

		/**
		 * {@inheritDoc}
		 * @see java.lang.Object#equals(java.lang.Object)
		 */
		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			SavedNotificationEvent other = (SavedNotificationEvent) obj;
			if (!getOuterType().equals(other.getOuterType()))
				return false;
			if (filter == null) {
				if (other.filter != null)
					return false;
			} else if (!filter.equals(other.filter))
				return false;
			if (handback == null) {
				if (other.handback != null)
					return false;
			} else if (!handback.equals(other.handback))
				return false;
			if (listener == null) {
				if (other.listener != null)
					return false;
			} else if (!listener.equals(other.listener))
				return false;
			if (listenerObjectName == null) {
				if (other.listenerObjectName != null)
					return false;
			} else if (!listenerObjectName.equals(other.listenerObjectName))
				return false;
			if (name == null) {
				if (other.name != null)
					return false;
			} else if (!name.equals(other.name))
				return false;
			return true;
		}

		private JMXClient getOuterType() {
			return JMXClient.this;
		}

		/**
		 * {@inheritDoc}
		 * @see java.lang.Object#toString()
		 */
		@Override
		public String toString() {
			StringBuilder builder = new StringBuilder();
			builder.append("SavedNotificationEvent [\\n\\t");
			if (name != null)
				builder.append("name:[").append(name).append("] ");
			if (listener != null)
				builder.append("listener:[").append(listener).append("] ");
			if (listenerObjectName != null)
				builder.append("listenerObjectName:[").append(listenerObjectName).append("] ");
			if (filter != null)
				builder.append("filter:[").append(filter).append("] ");
			if (handback != null)
				builder.append("handback:[").append(handback);
			builder.append("\\n]");
			return builder.toString();
		}
		
	}
	
}

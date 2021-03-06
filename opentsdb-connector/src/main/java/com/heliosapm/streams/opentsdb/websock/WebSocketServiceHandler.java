/**
 * Helios, OpenSource Monitoring
 * Brought to you by the Helios Development Group
 *
 * Copyright 2007, Helios Development Group and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org. 
 *
 */
package com.heliosapm.streams.opentsdb.websock;

import static org.jboss.netty.handler.codec.http.HttpHeaders.isKeepAlive;
import static org.jboss.netty.handler.codec.http.HttpHeaders.setContentLength;
import static org.jboss.netty.handler.codec.http.HttpMethod.GET;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelDownstreamHandler;
import org.jboss.netty.channel.ChannelEvent;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandler.Sharable;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelLocal;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelUpstreamHandler;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.DownstreamMessageEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import org.jboss.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heliosapm.streams.opentsdb.serialization.TSDBTypeSerializer;
import com.heliosapm.utils.lang.StringHelper;
import com.heliosapm.webrpc.jsonservice.JSONRequestRouter;
import com.heliosapm.webrpc.jsonservice.JSONResponse;
import com.heliosapm.webrpc.jsonservice.ResponseType;
import com.heliosapm.webrpc.jsonservice.netty3.Netty3ChannelBufferizable;
import com.heliosapm.webrpc.jsonservice.netty3.Netty3JSONRequest;
import com.heliosapm.webrpc.jsonservice.netty3.Netty3JSONResponse;
import com.heliosapm.webrpc.serialization.ChannelBufferizable;

import net.opentsdb.core.TSDB;
import net.opentsdb.tsd.BadRequestException;
import net.opentsdb.tsd.HttpRpcPluginQuery;
import net.opentsdb.tsd.TSDBJSONService;


/**
 * <p>Title: WebSocketServiceHandler</p>
 * <p>Description: WebSocket handler for fronting JSON based data-services</p> 
 * <p>Company: Helios Development Group LLC</p>
 * @author Whitehead (nwhitehead AT heliosdev DOT org)
 * <p><code>org.helios.tsdb.plugins.rpc.netty.pipeline.websock.WebSocketServiceHandler</code></p>
 */
@Sharable
public class WebSocketServiceHandler  implements ChannelUpstreamHandler, ChannelDownstreamHandler {
	/** The JSON Request Router */
	protected final JSONRequestRouter router = JSONRequestRouter.getInstance();
	protected final ObjectMapper marshaller = new ObjectMapper();	
	/** A channel local for the websocket handshaker */
	protected final ChannelLocal<WebSocketServerHandshaker> wsHandShaker = new ChannelLocal<WebSocketServerHandshaker>(true);
	/** Instance logger */
	protected final Logger log = LoggerFactory.getLogger(getClass());

	protected final AtomicBoolean rpcHandlerRegistered = new AtomicBoolean(false);
	/** The parent TSDB instance */
	protected final TSDB tsdb;
	
	
	
	/**
	 * Creates a new WebSocketServiceHandler
	 * @param tsdb The parent TSDB instance
	 */
	public WebSocketServiceHandler(final TSDB tsdb) {
		this.tsdb = tsdb;
		marshaller.configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);
	}
	
	/** The names of handlers to keep in the pipeline once the websock handshake is complete */
	public static final Set<String> WS_HANDLERS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
		"wsdecoder", "wsencoder", "timeout", "websock"
	)));
	
	/** The key of the rpc handler we'll keep a reference to */
	public static final String RPC_HANDLER = "handler";
	
	/**
	 * Registers the JSONRequestRouter on the first connect so we can acquire the RPCHandler from the pipeline.
	 * The RPCHandler is not a public class so we're referencing it as an Object.
	 * @param handler The RPCHandler
	 */
	protected void registerRpcHandler(final Object handler) {
		if(rpcHandlerRegistered.compareAndSet(false, true)) {
			JSONRequestRouter.getInstance().registerJSONService(new TSDBJSONService(tsdb, handler));
		}
	}
	
	
	
	

	/**
	 * {@inheritDoc}
	 * @see org.jboss.netty.channel.ChannelDownstreamHandler#handleDownstream(org.jboss.netty.channel.ChannelHandlerContext, org.jboss.netty.channel.ChannelEvent)
	 */
	@Override
	public void handleDownstream(ChannelHandlerContext ctx, ChannelEvent e) throws Exception {
		Channel channel = e.getChannel();
		if(!channel.isOpen()) return;
		if(!(e instanceof MessageEvent)) {
            ctx.sendDownstream(e);
            return;
        }
		Object message = ((MessageEvent)e).getMessage();
		if((message instanceof HttpResponse) || (message instanceof WebSocketFrame)) {
			ctx.sendDownstream(e);
			return;
		}
		if((message instanceof ChannelBuffer)) {
			ctx.sendDownstream(new DownstreamMessageEvent(channel, Channels.future(channel), new TextWebSocketFrame((ChannelBuffer)message), channel.getRemoteAddress()));
		} else if((message instanceof JsonNode)) {  			
			String json = marshaller.writeValueAsString(message);
			ctx.sendDownstream(new DownstreamMessageEvent(channel, Channels.future(channel), new TextWebSocketFrame(json), channel.getRemoteAddress()));			
		} else if((message instanceof ChannelBufferizable)) {
			ctx.sendDownstream(new DownstreamMessageEvent(channel, Channels.future(channel), new TextWebSocketFrame(((Netty3ChannelBufferizable)message).toChannelBuffer()), channel.getRemoteAddress()));
		} else if((message instanceof CharSequence)) {
			ctx.sendDownstream(new DownstreamMessageEvent(channel, Channels.future(channel), new TextWebSocketFrame(marshaller.writeValueAsString(message)), channel.getRemoteAddress()));
		} else if((message instanceof JSONResponse)) {				
			ObjectMapper mapper = (ObjectMapper)((JSONResponse)message).getChannelOption("mapper", TSDBTypeSerializer.DEFAULT.getMapper());			
			ctx.sendDownstream(new DownstreamMessageEvent(channel, Channels.future(channel), new TextWebSocketFrame(mapper.writeValueAsString(message)), channel.getRemoteAddress()));					
		} else {
            ctx.sendUpstream(e);
		}		
	}

	/**
	 * {@inheritDoc}
	 * @see org.jboss.netty.channel.ChannelUpstreamHandler#handleUpstream(org.jboss.netty.channel.ChannelHandlerContext, org.jboss.netty.channel.ChannelEvent)
	 */
	@Override
	public void handleUpstream(ChannelHandlerContext ctx, ChannelEvent e) throws Exception {
		log.warn("ChannelEvent: {}", e);
		if(e instanceof MessageEvent) {
			Object message = ((MessageEvent)e).getMessage();
			if (message instanceof HttpRequest) {
				handleRequest(ctx, (HttpRequest) message, (MessageEvent)e);
			} else if (message instanceof WebSocketFrame) {
				handleRequest(ctx, (WebSocketFrame) message);
			}
		} else {
			ctx.sendUpstream(e);
		}			
	}
	
	/**
	 * Handles uncaught exceptions in the pipeline from this handler
	 * @param ctx The channel context
	 * @param ev The exception event
	 */
	public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent ev) {
		log.error("Uncaught exception ", ev.getCause());
	}
	
	/**
	 * Processes a websocket request
	 * @param ctx The channel handler context
	 * @param frame The websocket frame request to process
	 */
	public void handleRequest(ChannelHandlerContext ctx, WebSocketFrame frame) {
        // Check for closing frame
        if (frame instanceof CloseWebSocketFrame) {
        	wsHandShaker.get(ctx.getChannel()).close(ctx.getChannel(), (CloseWebSocketFrame) frame);
            return;
        } else if (frame instanceof PingWebSocketFrame) {
            ctx.getChannel().write(new PongWebSocketFrame(frame.getBinaryData()));
            return;
        } else if (!(frame instanceof TextWebSocketFrame)) {
            throw new UnsupportedOperationException(String.format("%s frame types not supported", frame.getClass()
                    .getName()));
        }
        String request = ((TextWebSocketFrame) frame).getText();
        Netty3JSONRequest wsRequest = null;
        try {
        	wsRequest = Netty3JSONRequest.newJSONRequest(ctx.getChannel(), request);
        	
//        	if("who".equals(wsRequest.getArgument("t").toString())) {
//        		SocketAddress sa = ctx.getChannel().getRemoteAddress();
//        		String host = "unknown";
//        		String agent = "unknown";
//        		if(sa!=null) {
//        			host = ((InetSocketAddress)sa).getHostName();        			
//        		}
//        		if(wsRequest.getArgument("agent")!=null) {
//        			agent = wsRequest.getArgument("agent").toString();
//        		}
//        		SharedChannelGroup.getInstance().add(ctx.getChannel(), ChannelType.WEBSOCKET_REMOTE, "ClientWebSocket", host, agent);
//        	} else {        		
        		router.route(wsRequest);
//        	}
        	
        		
        } catch (Exception ex) {
        	Netty3JSONResponse response = new Netty3JSONResponse(-1, ResponseType.ERR, ctx.getChannel(), wsRequest);
    		Map<String, String> map = new HashMap<String, String>(2);
    		map.put("err", "Failed to parse request [" + request + "]");
    		map.put("ex", StringHelper.formatStackTrace(ex));
    		response.setContent(map);
        	log.error("Failed to parse request [" + request + "]", ex);
        	ctx.getChannel().write(response);
        }		
	}
	
	/**
	 * Processes an HTTP request
	 * @param ctx The channel handler context
	 * @param req The HTTP request
	 * @param me The message event that will be sent upstream if not handled here
	 */
	public void handleRequest(ChannelHandlerContext ctx, HttpRequest req, MessageEvent me) {
		log.warn("HTTP Request: {}", req);
        if (req.getMethod() != GET) {
            sendHttpResponse(ctx, req, new DefaultHttpResponse(HTTP_1_1, FORBIDDEN));
            return;
        }
        String uri = req.getUri();
        if(!"/ws".equals(uri)) {
        	ctx.sendUpstream(me);
        	return;
        }
        final Channel channel = me.getChannel();
        // Handshake
        WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(getWebSocketLocation(req), null, false);
        WebSocketServerHandshaker handshaker = wsFactory.newHandshaker(req);
        if (handshaker == null) {
            wsFactory.sendUnsupportedWebSocketVersionResponse(channel);
        } else {
        	wsHandShaker.set(channel, handshaker);
        	ChannelFuture cf = handshaker.handshake(channel, req); 
            cf.addListener(WebSocketServerHandshaker.HANDSHAKE_LISTENER);
            cf.addListener(new ChannelFutureListener() {
				@Override
				public void operationComplete(ChannelFuture f) throws Exception {
					if(f.isSuccess()) {
						final Channel wsChannel = f.getChannel();
//						RPCSessionManager.getInstance().getSession(wsChannel).addSessionAttribute(RPCSessionAttribute.Protocol, "WebSocket");
//						SharedChannelGroup.getInstance().add(
//								f.getChannel(), 
//								ChannelType.WEBSOCKET_REMOTE, 
//								"WebSocketClient-" + f.getChannel().getId(), 
//								((InetSocketAddress)wsChannel.getRemoteAddress()).getAddress().getCanonicalHostName(), 
//								"WebSock[" + wsChannel.getId() + "]"
//						);
						//wsChannel.write(new JSONObject(Collections.singletonMap("sessionid", wsChannel.getId())));
						
						wsChannel.write(marshaller.getNodeFactory().objectNode().put("sessionid", "" + wsChannel.getId()));
						//wsChannel.getPipeline().remove(DefaultChannelHandler.NAME);
					}
				}
			});
        }
	}
	
    /**
     * Generates a websocket URL for the passed request
     * @param req The http request
     * @return The websocket URL
     */
    private String getWebSocketLocation(final HttpRequest req) {    	
        return "ws://" + HttpHeaders.getHost(req) + "/ws";
    }	
	
    /**
     * Sends an HTTP response
     * @param ctx The channel handler context
     * @param req The HTTP request being responded to
     * @param res The HTTP response to send
     */
    private void sendHttpResponse(ChannelHandlerContext ctx, HttpRequest req, HttpResponse res) {
        // Generate an error page if response status code is not OK (200).
        if (res.getStatus().getCode() != 200) {
            res.setContent(ChannelBuffers.copiedBuffer(res.getStatus().toString(), CharsetUtil.UTF_8));
            setContentLength(res, res.getContent().readableBytes());
        }

        // Send the response and close the connection if necessary.
        ChannelFuture f = ctx.getChannel().write(res);
        if (!isKeepAlive(req) || res.getStatus().getCode() != 200) {
            f.addListener(ChannelFutureListener.CLOSE);
        }
    }

	
	public void execute(final TSDB tsdb, final HttpRpcPluginQuery query) throws IOException {
		
		if(query.method()!=GET) {
			query.badRequest(new BadRequestException("HTTP Request Type [" + query.method() + "] Forbidden"));
			return;
		}
        
        final Channel channel = query.channel();
        final  HttpRequest req = query.request();        
        final WebSocketServiceHandler wsHandler = this;
        // Handshake
        WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(getWebSocketLocation(req), null, false);
        WebSocketServerHandshaker handshaker = wsFactory.newHandshaker(req);
        if (handshaker == null) {
            wsFactory.sendUnsupportedWebSocketVersionResponse(channel);
        } else {
        	wsHandShaker.set(channel, handshaker);
        	ChannelFuture cf = handshaker.handshake(channel, req); 
            cf.addListener(WebSocketServerHandshaker.HANDSHAKE_LISTENER);
            cf.addListener(new ChannelFutureListener() {
				@Override
				public void operationComplete(final ChannelFuture f) throws Exception {
					if(f.isSuccess()) {
						Channel wsChannel = f.getChannel();
						final ChannelPipeline p = wsChannel.getPipeline(); 
						p.addLast("websock", wsHandler);
						registerRpcHandler(p.get(RPC_HANDLER));
						final Set<String> handlerNames = new HashSet<String>(p.getNames());
						for(final String key: handlerNames) {
							if(!WS_HANDLERS.contains(key)) {
								p.remove(key);
							}
						}
						StringBuilder b = new StringBuilder("\n\t=================================\n\tWS Channel Handlers\n\t=================================");
						for(String key: wsChannel.getPipeline().getNames()) {
							b.append("\n\t").append(key).append(" : ").append(wsChannel.getPipeline().get(key).getClass().getName());
						}
						log.info(b.toString());
//						RPCSessionManager.getInstance().getSession(wsChannel).addSessionAttribute(RPCSessionAttribute.Protocol, "WebSocket");
//						SharedChannelGroup.getInstance().add(
//								f.getChannel(), 
//								ChannelType.WEBSOCKET_REMOTE, 
//								"WebSocketClient-" + f.getChannel().getId(), 
//								((InetSocketAddress)wsChannel.getRemoteAddress()).getAddress().getCanonicalHostName(), 
//								"WebSock[" + wsChannel.getId() + "]"
//						);
						//wsChannel.write(new JSONObject(Collections.singletonMap("sessionid", wsChannel.getId())));
						
						wsChannel.write(marshaller.getNodeFactory().objectNode().put("sessionid", "" + wsChannel.getId()));
						//wsChannel.getPipeline().remove(DefaultChannelHandler.NAME);
					} else {
						log.error("WS Handshake Failed", f.getCause());
					}
				}
			});
        }
		
	}
    

}

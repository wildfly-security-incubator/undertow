/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.undertow.servlet.spec;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.servlet.AsyncContext;
import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.Part;

import io.undertow.server.handlers.blocking.BlockingHttpServerExchange;
import io.undertow.server.handlers.form.FormData;
import io.undertow.server.handlers.form.FormDataParser;
import io.undertow.server.handlers.form.MultiPartHandler;
import io.undertow.server.session.Session;
import io.undertow.server.session.SessionManager;
import io.undertow.servlet.UndertowServletMessages;
import io.undertow.servlet.util.EmptyEnumeration;
import io.undertow.servlet.util.IteratorEnumeration;
import io.undertow.util.AttachmentKey;
import io.undertow.util.CanonicalPathUtils;
import io.undertow.util.DateUtils;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.Methods;
import org.xnio.LocalSocketAddress;

/**
 * The http servlet request implementation. This class is not thread safe
 *
 * @author Stuart Douglas
 */
public class HttpServletRequestImpl implements HttpServletRequest {

    public static final AttachmentKey<ServletRequest> ATTACHMENT_KEY = AttachmentKey.create(ServletRequest.class);
    public static final AttachmentKey<DispatcherType> DISPATCHER_TYPE_ATTACHMENT_KEY = AttachmentKey.create(DispatcherType.class);

    private final BlockingHttpServerExchange exchange;
    private volatile ServletContextImpl servletContext;


    private final HashMap<String, Object> attributes = new HashMap<String, Object>();

    private ServletInputStream servletInputStream;
    private BufferedReader reader;

    private Cookie[] cookies;
    private volatile List<Part> parts = null;
    private HttpSessionImpl httpSession;
    private AsyncContextImpl asyncContext = null;
    private Map<String, Deque<String>> queryParameters;

    public HttpServletRequestImpl(final BlockingHttpServerExchange exchange, final ServletContextImpl servletContext) {
        this.exchange = exchange;
        this.servletContext = servletContext;
        this.queryParameters = exchange.getExchange().getQueryParameters();
    }

    public BlockingHttpServerExchange getExchange() {
        return exchange;
    }

    @Override
    public String getAuthType() {
        return null;
    }

    @Override
    public Cookie[] getCookies() {
        if (cookies == null) {
            Map<String, io.undertow.server.handlers.Cookie> cookies = io.undertow.server.handlers.Cookie.getRequestCookies(exchange.getExchange());
            if (cookies.isEmpty()) {
                return null;
            }
            Cookie[] value = new Cookie[cookies.size()];
            int i = 0;
            for (Map.Entry<String, io.undertow.server.handlers.Cookie> entry : cookies.entrySet()) {
                io.undertow.server.handlers.Cookie cookie = entry.getValue();
                Cookie c = new Cookie(cookie.getName(), cookie.getValue());
                if (cookie.getDomain() != null) {
                    c.setDomain(cookie.getDomain());
                }
                c.setHttpOnly(cookie.isHttpOnly());
                if (cookie.getMaxAge() != null) {
                    c.setMaxAge(cookie.getMaxAge());
                }
                if (cookie.getPath() != null) {
                    c.setPath(cookie.getPath());
                }
                c.setSecure(cookie.isSecure());
                c.setVersion(cookie.getVersion());
                value[i++] = c;
            }
            this.cookies = value;
        }
        return cookies;
    }

    @Override
    public long getDateHeader(final String name) {
        String header = exchange.getExchange().getRequestHeaders().getFirst(new HttpString(name));
        if (header == null) {
            return -1;
        }
        Date date = DateUtils.parseDate(header);
        if (date == null) {
            throw UndertowServletMessages.MESSAGES.headerCannotBeConvertedToDate(header);
        }
        return date.getTime();
    }

    @Override
    public String getHeader(final String name) {
        return getHeader(new HttpString(name));
    }

    public String getHeader(final HttpString name) {
        HeaderMap headers = exchange.getExchange().getRequestHeaders();
        if (headers == null) {
            return null;
        }
        return headers.getFirst(name);
    }


    @Override
    public Enumeration<String> getHeaders(final String name) {
        Deque<String> headers = exchange.getExchange().getRequestHeaders().get(new HttpString(name));
        if (headers == null) {
            return EmptyEnumeration.instance();
        }
        return new IteratorEnumeration<String>(headers.iterator());
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        final Set<String> headers = new HashSet<String>();
        for(final HttpString i : exchange.getExchange().getRequestHeaders()) {
            headers.add(i.toString());
        }
        return new IteratorEnumeration<String>(headers.iterator());
    }

    @Override
    public int getIntHeader(final String name) {
        String header = getHeader(name);
        if (header == null) {
            return -1;
        }
        return Integer.parseInt(header);
    }

    @Override
    public String getMethod() {
        return exchange.getExchange().getRequestMethod().toString();
    }

    @Override
    public String getPathInfo() {
        return exchange.getExchange().getRelativePath();
    }

    @Override
    public String getPathTranslated() {
        return null;
    }

    @Override
    public String getContextPath() {
        return servletContext.getContextPath();
    }

    @Override
    public String getQueryString() {
        return exchange.getExchange().getQueryString();
    }

    @Override
    public String getRemoteUser() {
        return null;
    }

    @Override
    public boolean isUserInRole(final String role) {
        return false;
    }

    @Override
    public Principal getUserPrincipal() {
        return null;
    }

    @Override
    public String getRequestedSessionId() {
        return null;
    }

    @Override
    public String getRequestURI() {
        return exchange.getExchange().getRequestPath();
    }

    @Override
    public StringBuffer getRequestURL() {
        return new StringBuffer(exchange.getExchange().getRequestURL());
    }

    @Override
    public String getServletPath() {
        return exchange.getExchange().getRelativePath();
    }

    @Override
    public HttpSession getSession(final boolean create) {
        if (httpSession == null) {
            Session session = exchange.getExchange().getAttachment(Session.ATTACHMENT_KEY);
            if (session != null) {
                httpSession = new HttpSessionImpl(session, servletContext, servletContext.getDeployment().getApplicationListeners(), exchange.getExchange(), false);
            } else if (create) {
                final SessionManager sessionManager = exchange.getExchange().getAttachment(SessionManager.ATTACHMENT_KEY);
                try {
                    Session newSession = sessionManager.createSession(exchange.getExchange()).get();
                    httpSession = new HttpSessionImpl(newSession, servletContext, servletContext.getDeployment().getApplicationListeners(), exchange.getExchange(), true);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return httpSession;
    }

    @Override
    public HttpSession getSession() {
        return getSession(true);
    }

    @Override
    public boolean isRequestedSessionIdValid() {
        return false;
    }

    @Override
    public boolean isRequestedSessionIdFromCookie() {
        return false;
    }

    @Override
    public boolean isRequestedSessionIdFromURL() {
        return false;
    }

    @Override
    public boolean isRequestedSessionIdFromUrl() {
        return false;
    }

    @Override
    public boolean authenticate(final HttpServletResponse response) throws IOException, ServletException {
        return false;
    }

    @Override
    public void login(final String username, final String password) throws ServletException {

    }

    @Override
    public void logout() throws ServletException {

    }

    @Override
    public Collection<Part> getParts() throws IOException, ServletException {
        if (parts == null) {
            loadParts();
        }
        return parts;
    }

    @Override
    public Part getPart(final String name) throws IOException, ServletException {
        if (parts == null) {
            loadParts();
        }
        for (Part part : parts) {
            if (part.getName().equals(name)) {
                return part;
            }
        }
        return null;
    }

    private synchronized void loadParts() throws IOException, ServletException {
        if (parts == null) {
            final List<Part> parts = new ArrayList<Part>();
            String mimeType = exchange.getExchange().getRequestHeaders().getFirst(Headers.CONTENT_TYPE);
            if (mimeType != null && mimeType.startsWith(MultiPartHandler.MULTIPART_FORM_DATA)) {
                final FormDataParser parser = exchange.getExchange().getAttachment(FormDataParser.ATTACHMENT_KEY);
                final FormData value = parser.parseBlocking();
                for (final String namedPart : value) {
                    for (FormData.FormValue part : value.get(namedPart)) {
                        //TODO: non-file parts?
                        parts.add(new PartImpl(namedPart, part));
                    }
                }
            } else {
                throw UndertowServletMessages.MESSAGES.notAMultiPartRequest();
            }
            this.parts = parts;
        }
    }

    @Override
    public Object getAttribute(final String name) {
        return attributes.get(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return new IteratorEnumeration<String>(attributes.keySet().iterator());
    }

    @Override
    public String getCharacterEncoding() {
        return null;
    }

    @Override
    public void setCharacterEncoding(final String env) throws UnsupportedEncodingException {


    }

    @Override
    public int getContentLength() {
        final String contentLength = getHeader(Headers.CONTENT_LENGTH);
        if (contentLength == null || contentLength.isEmpty()) {
            return -1;
        }
        return Integer.parseInt(contentLength);
    }

    @Override
    public String getContentType() {
        return getHeader(Headers.CONTENT_TYPE);
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (servletInputStream == null) {
            if (reader != null) {
                throw UndertowServletMessages.MESSAGES.getReaderAlreadyCalled();
            }
            servletInputStream = new ServletInputStreamImpl(exchange.getInputStream());
        }
        return servletInputStream;
    }

    @Override
    public String getParameter(final String name) {
        Deque<String> params = queryParameters.get(name);
        if (params == null) {
            if (exchange.getExchange().getRequestMethod().equals(Methods.POST)) {
                final FormDataParser parser = exchange.getExchange().getAttachment(FormDataParser.ATTACHMENT_KEY);
                if (parser != null) {
                    try {
                        FormData.FormValue res = parser.parseBlocking().getFirst(name);
                        if (res == null) {
                            return null;
                        } else {
                            return res.getValue();
                        }

                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            return null;
        }
        return params.getFirst();
    }

    @Override
    public Enumeration<String> getParameterNames() {
        final Set<String> parameterNames = new HashSet<String>(queryParameters.keySet());
        if (exchange.getExchange().getRequestMethod().equals(Methods.POST)) {
            final FormDataParser parser = exchange.getExchange().getAttachment(FormDataParser.ATTACHMENT_KEY);
            if (parser != null) {
                try {
                    FormData formData = parser.parseBlocking();
                    Iterator<String> it = formData.iterator();
                    while (it.hasNext()) {
                        parameterNames.add(it.next());
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return new IteratorEnumeration<String>(parameterNames.iterator());
    }

    @Override
    public String[] getParameterValues(final String name) {
        final List<String> ret = new ArrayList<String>();
        Deque<String> params = queryParameters.get(name);
        if (params != null) {
            ret.addAll(params);
        }
        if (exchange.getExchange().getRequestMethod().equals(Methods.POST)) {
            final FormDataParser parser = exchange.getExchange().getAttachment(FormDataParser.ATTACHMENT_KEY);
            if (parser != null) {
                try {
                    Deque<FormData.FormValue> res = parser.parseBlocking().get(name);
                    if (res == null) {
                        return null;
                    } else {
                        for (FormData.FormValue value : res) {
                            ret.add(value.getValue());
                        }
                    }

                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        if (ret.isEmpty()) {
            return null;
        }
        return ret.toArray(new String[ret.size()]);
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        final Map<String, String[]> ret = new HashMap<String, String[]>();
        for (Map.Entry<String, Deque<String>> entry : queryParameters.entrySet()) {
            ret.put(entry.getKey(), entry.getValue().toArray(new String[entry.getValue().size()]));
        }
        if (exchange.getExchange().getRequestMethod().equals(Methods.POST)) {
            final FormDataParser parser = exchange.getExchange().getAttachment(FormDataParser.ATTACHMENT_KEY);
            if (parser != null) {
                try {
                    FormData formData = parser.parseBlocking();
                    Iterator<String> it = formData.iterator();
                    while (it.hasNext()) {
                        final String name = it.next();
                        Deque<FormData.FormValue> val = formData.get(name);
                        if (ret.containsKey(name)) {
                            String[] existing = ret.get(name);
                            String[] array = new String[val.size() + existing.length];
                            System.arraycopy(existing, 0, array, 0, existing.length);
                            int i = existing.length;
                            for (final FormData.FormValue v : val) {
                                array[i++] = v.getValue();
                            }
                            ret.put(name, array);
                        } else {
                            String[] array = new String[val.size()];
                            int i = 0;
                            for (final FormData.FormValue v : val) {
                                array[i++] = v.getValue();
                            }
                            ret.put(name, array);
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return ret;
    }

    @Override
    public String getProtocol() {
        return exchange.getExchange().getProtocol().toString();
    }

    @Override
    public String getScheme() {
        return exchange.getExchange().getRequestScheme();
    }

    @Override
    public String getServerName() {
        return exchange.getExchange().getSourceAddress().getHostName();
    }

    @Override
    public int getServerPort() {
        return exchange.getExchange().getSourceAddress().getPort();
    }

    @Override
    public BufferedReader getReader() throws IOException {
        if (reader == null) {
            if (servletInputStream != null) {
                throw UndertowServletMessages.MESSAGES.getInputStreamAlreadyCalled();
            }
            reader = new BufferedReader(new InputStreamReader(exchange.getInputStream()));
        }
        return reader;
    }

    @Override
    public String getRemoteAddr() {
        return exchange.getExchange().getSourceAddress().getAddress().getHostAddress();
    }

    @Override
    public String getRemoteHost() {
        return exchange.getExchange().getSourceAddress().getHostName();
    }

    @Override
    public void setAttribute(final String name, final Object object) {
        Object existing = attributes.put(name, object);
        if (existing != null) {
            servletContext.getDeployment().getApplicationListeners().servletRequestAttributeReplaced(this, name, existing);
        } else {
            servletContext.getDeployment().getApplicationListeners().servletRequestAttributeAdded(this, name, object);
        }
    }

    @Override
    public void removeAttribute(final String name) {
        Object exiting = attributes.remove(name);
        servletContext.getDeployment().getApplicationListeners().servletRequestAttributeRemoved(this, name, exiting);
    }

    @Override
    public Locale getLocale() {
        return Locale.getDefault();
    }

    @Override
    public Enumeration<Locale> getLocales() {
        return null;
    }

    @Override
    public boolean isSecure() {
        return false;
    }

    @Override
    public RequestDispatcher getRequestDispatcher(final String path) {
        String realPath;
        if(path.startsWith("/")) {
            realPath = path;
        } else {
            String current = exchange.getExchange().getRelativePath();
            int lastSlash = current.lastIndexOf("/");
            if(lastSlash != -1) {
                current = current.substring(0, lastSlash + 1);
            }
            realPath = CanonicalPathUtils.canonicalize(current + path);
        }
        return new RequestDispatcherImpl(realPath, servletContext, servletContext.getDeployment().getServletPaths().getServletHandlerByPath(realPath));
    }

    @Override
    public String getRealPath(final String path) {
        return null;
    }

    @Override
    public int getRemotePort() {
        return exchange.getExchange().getSourceAddress().getPort();
    }

    @Override
    public String getLocalName() {
        return null;
    }

    @Override
    public String getLocalAddr() {
        SocketAddress address = exchange.getExchange().getConnection().getLocalAddress();
        if (address instanceof InetSocketAddress) {
            return ((InetSocketAddress) address).getHostName();
        } else if (address instanceof LocalSocketAddress) {
            return ((LocalSocketAddress) address).getName();
        }
        return null;
    }

    @Override
    public int getLocalPort() {
        SocketAddress address = exchange.getExchange().getConnection().getLocalAddress();
        if (address instanceof InetSocketAddress) {
            return ((InetSocketAddress) address).getPort();
        }
        return -1;
    }

    @Override
    public ServletContext getServletContext() {
        return servletContext;
    }

    @Override
    public AsyncContext startAsync() throws IllegalStateException {
        return asyncContext = new AsyncContextImpl(exchange.getExchange(), exchange.getExchange().getAttachment(HttpServletRequestImpl.ATTACHMENT_KEY), exchange.getExchange().getAttachment(HttpServletResponseImpl.ATTACHMENT_KEY));
    }

    @Override
    public AsyncContext startAsync(final ServletRequest servletRequest, final ServletResponse servletResponse) throws IllegalStateException {
        return asyncContext = new AsyncContextImpl(exchange.getExchange(), servletRequest, servletResponse);
    }

    @Override
    public boolean isAsyncStarted() {
        return asyncContext != null;
    }

    @Override
    public boolean isAsyncSupported() {
        Boolean supported = exchange.getExchange().getAttachment(AsyncContextImpl.ASYNC_SUPPORTED);
        return supported == null || supported;
    }

    @Override
    public AsyncContext getAsyncContext() {
        if (asyncContext == null) {
            throw UndertowServletMessages.MESSAGES.asyncNotStarted();
        }
        return asyncContext;
    }

    @Override
    public DispatcherType getDispatcherType() {
        return exchange.getExchange().getAttachment(DISPATCHER_TYPE_ATTACHMENT_KEY);
    }

    public Map<String, Deque<String>> getQueryParameters() {
        return queryParameters;
    }

    public void setQueryParameters(final Map<String, Deque<String>> queryParameters) {
        this.queryParameters = queryParameters;
    }

    public void setServletContext(final ServletContextImpl servletContext) {
        this.servletContext = servletContext;
    }
}

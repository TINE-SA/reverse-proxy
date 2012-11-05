/*
This file is part of Tek271 Reverse Proxy Server.

Tek271 Reverse Proxy Server is free software: you can redistribute it and/or modify
it under the terms of the GNU Lesser General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

Tek271 Reverse Proxy Server is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Lesser General Public License for more details.

You should have received a copy of the GNU Lesser General Public License
along with Tek271 Reverse Proxy Server.  If not, see http://www.gnu.org/licenses/
 */
package com.tek271.reverseProxy.servlet;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.tek271.reverseProxy.model.Mapping;
import com.tek271.reverseProxy.text.UrlMapper;
import com.tek271.reverseProxy.utils.HttpDeleteWithBody;
import com.tek271.reverseProxy.utils.Tuple2;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;

public class ProxyFilter implements Filter {

    public void init(FilterConfig filterConfig) throws ServletException {
    }

    public void destroy() {
    }

    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!isHttp(request, response)) {
            return;
        }

        Tuple2<Mapping, String> mapped = mapUrlProxyToHidden(request);
        if (mapped.isNull()) {
            chain.doFilter(request, response);
            return;
        }

        executeRequest(response, mapped.e1, mapped.e2, ((HttpServletRequest) request));
    }

    private static boolean isHttp(ServletRequest request, ServletResponse response) {
        return (request instanceof HttpServletRequest) && (response instanceof HttpServletResponse);
    }

    private static Tuple2<Mapping, String> mapUrlProxyToHidden(ServletRequest request) {
        String oldUrl = ((HttpServletRequest) request).getRequestURL().toString();
        String queryString = ((HttpServletRequest) request).getQueryString();
        if (queryString != null) {
            oldUrl += "?" + queryString;
        }
        return UrlMapper.mapFullUrlProxyToHidden(oldUrl);
    }

    /**
     * Helper method for passing post-requests
     */
    @SuppressWarnings({ "JavaDoc" })
    private static HttpUriRequest createNewRequest(HttpServletRequest request, String newUrl) throws UnsupportedEncodingException {
        String method = request.getMethod();
        if (method.equals("POST")) {
            UrlEncodedFormEntity entity = getEntity(request);
            HttpPost httppost = new HttpPost(newUrl);
            httppost.setEntity(entity);
            addCustomHeaders(request, httppost);
            return httppost;
        } else if (method.equals("PUT")) {
            UrlEncodedFormEntity entity = getEntity(request);
            HttpPut httpPut = new HttpPut(newUrl);
            httpPut.setEntity(entity);
            addCustomHeaders(request, httpPut);
            return httpPut;
        } else if (method.equals("DELETE")) {
            UrlEncodedFormEntity entity = getEntity(request);
            HttpDeleteWithBody httpDelete = new HttpDeleteWithBody(newUrl);
            httpDelete.setEntity(entity);
            addCustomHeaders(request, httpDelete);
            return httpDelete;
        } else {
            HttpGet httpGet = new HttpGet(newUrl);
            httpGet.addHeader("preferred-role", request.getHeader("preferred-role"));
            return httpGet;
        }
    }

    @SuppressWarnings({ "unchecked" })
    private static void addCustomHeaders(HttpServletRequest original, HttpEntityEnclosingRequest request) {
        Enumeration<String> en = original.getHeaderNames();
        while (en.hasMoreElements()) {
            String name = en.nextElement();
            if ("X-HTTP-Method-Override".equals(name)) {
                request.setHeader(name, original.getHeader(name));
            } else if ("preferred-role".equals(name)) {
                request.setHeader(name, original.getHeader(name));
            }
        }
    }

    @SuppressWarnings({ "unchecked" })
    private static UrlEncodedFormEntity getEntity(HttpServletRequest request)
            throws UnsupportedEncodingException {
        List<NameValuePair> formparams = new ArrayList<NameValuePair>();
        Enumeration<String> en = request.getParameterNames();
        while (en.hasMoreElements()) {
            String name = en.nextElement();
            String value = request.getParameter(name);
            formparams.add(new BasicNameValuePair(name, value));
        }
        return new UrlEncodedFormEntity(formparams, "UTF-8");
    }

    private static void executeRequest(ServletResponse response, Mapping mapping, String newUrl, HttpServletRequest request)
            throws IOException {
        HttpClient httpclient = new DefaultHttpClient();
        HttpUriRequest httpRequest = createNewRequest(request, newUrl);
        HttpResponse r = httpclient.execute(httpRequest);
        HttpEntity entity = r.getEntity();

        ContentTranslator contentTranslator = new ContentTranslator(mapping, newUrl);
        contentTranslator.translate(entity, response);
        contentTranslator.translateResponseCode(r, response);
    }

}

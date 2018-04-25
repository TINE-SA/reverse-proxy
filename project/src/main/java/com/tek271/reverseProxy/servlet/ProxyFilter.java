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

import java.io.*;
import java.util.*;
import java.util.stream.Stream;
import javax.servlet.*;
import javax.servlet.http.*;

import com.google.common.base.Charsets;
import com.tek271.reverseProxy.model.Mapping;
import com.tek271.reverseProxy.text.UrlMapper;
import com.tek271.reverseProxy.utils.*;
import org.apache.commons.fileupload.*;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.http.*;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.*;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.*;
import org.apache.http.entity.mime.content.*;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;

import static java.util.stream.Collectors.toSet;

public class ProxyFilter implements Filter {
    private static final String APPLICATION_JSON = "application/json";
    private static final Collection<String> HEADERS_FOR_FORWARDING = Stream.of(
            "X-HTTP-Method-Override",
            "X-Source",
            "preferred-role",
            "X-File-Name",
            "X-Requested-With",
            "X-Requested-By",
            "Content-type",
            "Accept",
            "iv-user",
            "preferred-role",
            "iv-groups"
    ).map(String::toLowerCase).collect(toSet());

    @Override
    public void init(FilterConfig filterConfig) {
        // do nothing
    }

    @Override
    public void destroy() {
        // do nothing
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
    @SuppressWarnings({ "JavaDoc", "IfCanBeSwitch" })
    private static HttpUriRequest createNewRequest(HttpServletRequest request, String newUrl) throws IOException {
        String method = request.getMethod();
        if (method.equals("POST")) {
            HttpPost httppost = new HttpPost(newUrl);
            if (ServletFileUpload.isMultipartContent(request)) {
                MultipartEntity entity = getMultipartEntity(request);
                httppost.setEntity(entity);
                addCustomHeaders(request, httppost, "Content-Type");
            } else {
                StringEntity entity = getEntity(request);
                httppost.setEntity(entity);
                addCustomHeaders(request, httppost);
            }
            return httppost;
        } else if (method.equals("PUT")) {
            StringEntity entity = getEntity(request);
            HttpPut httpPut = new HttpPut(newUrl);
            httpPut.setEntity(entity);
            addCustomHeaders(request, httpPut);
            return httpPut;
        } else if (method.equals("DELETE")) {
            StringEntity entity = getEntity(request);
            HttpDeleteWithBody httpDelete = new HttpDeleteWithBody(newUrl);
            httpDelete.setEntity(entity);
            addCustomHeaders(request, httpDelete);
            return httpDelete;
        } else {
            HttpGet httpGet = new HttpGet(newUrl);
            addCustomHeaders(request, httpGet);
            return httpGet;
        }
    }

    private static MultipartEntity getMultipartEntity(HttpServletRequest request) {
        MultipartEntity entity = new MultipartEntity(HttpMultipartMode.BROWSER_COMPATIBLE);
        @SuppressWarnings("unchecked") Enumeration<String> en = request.getParameterNames();
        while (en.hasMoreElements()) {
            String name = en.nextElement();
            String value = request.getParameter(name);
            try {
                if (name.equals("file")) {
                    FileItemFactory factory = new DiskFileItemFactory();
                    ServletFileUpload upload = new ServletFileUpload(factory);
                    upload.setSizeMax(10000000);// 10 Mo
                    @SuppressWarnings("unchecked") List<FileItem> items = upload.parseRequest(request);
                    for (FileItem item : items) {
                        File file = new File(item.getName());
                        try (FileOutputStream fos = new FileOutputStream(file)) {
                            fos.write(item.get());
                            fos.flush();
                        }
                        entity.addPart(name, new FileBody(file, "application/zip"));
                    }
                } else {
                    entity.addPart(name, new StringBody(value, "text/plain", Charsets.UTF_8));
                }
            } catch (FileUploadException | IOException e) {
                e.printStackTrace();
            }
        }

        return entity;

    }

    @SuppressWarnings({ "unchecked" })
    private static void addCustomHeaders(HttpServletRequest original, HttpUriRequest request, String... skipElements) {
        Enumeration<String> en = original.getHeaderNames();
        while (en.hasMoreElements()) {
            String name = en.nextElement();
            if (!contains(skipElements, name) && HEADERS_FOR_FORWARDING.contains(name.toLowerCase())) {
                request.setHeader(name, original.getHeader(name));
            }
        }
    }

    private static boolean contains(String[] skipElements, String name) {
        if (skipElements == null || skipElements.length == 0) {
            return false;
        }
        for (String skipElement : skipElements) {
            if (skipElement.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings({ "unchecked" })
    private static StringEntity getEntity(HttpServletRequest request)
            throws IOException {
        if (APPLICATION_JSON.equalsIgnoreCase(request.getHeader("Content-type"))) {
            StringBuilder stringBuilder = new StringBuilder();
            BufferedReader bufferedReader;
            InputStream inputStream = request.getInputStream();
            if (inputStream != null) {
                bufferedReader = new BufferedReader(new InputStreamReader(inputStream, Charsets.UTF_8));
                char[] charBuffer = new char[512];
                int bytesRead;
                while ((bytesRead = bufferedReader.read(charBuffer)) > 0) {
                    stringBuilder.append(charBuffer, 0, bytesRead);
                }
                bufferedReader.close();
            }
            return new StringEntity(stringBuilder.toString(), "UTF-8");

        }
        List<NameValuePair> formparams = new ArrayList<>();
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
        contentTranslator.updateHeaders(r, response);
        contentTranslator.translate(r, entity, response);
    }

}

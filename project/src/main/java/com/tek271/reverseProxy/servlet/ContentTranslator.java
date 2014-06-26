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

import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;

import com.tek271.reverseProxy.model.Mapping;
import com.tek271.reverseProxy.text.TextTranslator;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;

public class ContentTranslator {

    private final Mapping mapping;
    private final String newUrl;

    public ContentTranslator(Mapping mapping, String newUrl) {
        this.mapping = mapping;
        this.newUrl = newUrl;
    }

    public void translate(HttpResponse r, HttpEntity entity, ServletResponse response) {
        if (entity == null) {
            return;
        }

        ContentType contentType = new ContentType(entity.getContentType(), newUrl);
        if (contentType.isMultipart) {
            ContentUtils.copyBinary(entity, response);
            translateResponseCode(r, response, "");
            return;
        }
        if (contentType.isBinary) {
            ContentUtils.copyBinary(entity, response);
            translateResponseCode(r, response, "");
            return;
        }
        String text = ContentUtils.getContentText(entity, contentType.charset);
        response.setContentType(contentType.value);

        if (!contentType.isJavaScript) {
            text = translateText(text);
        }

        translateResponseCode(r, response, text);
        ContentUtils.copyText(text, response);
    }

    private String translateText(String text) {
        TextTranslator textTranslator = new TextTranslator(mapping);
        return textTranslator.translate(text);
    }

    public void translateResponseCode(HttpResponse r, ServletResponse response, String text) {
        if (response instanceof HttpServletResponse) {
            int statusCode = r.getStatusLine().getStatusCode();
            ((HttpServletResponse) response).setStatus(statusCode);
        }
    }

    public void updateHeaders(HttpResponse r, ServletResponse response) {
        if (response instanceof HttpServletResponse) {
            Header[] allHeaders = r.getAllHeaders();
            for (Header header : allHeaders) {
                ((HttpServletResponse) response).setHeader(header.getName(), header.getValue());
            }

        }
    }
}

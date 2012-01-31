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
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import javax.servlet.*;
import javax.servlet.http.*;

import org.apache.http.*;
import org.apache.http.client.*;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpParams;

import com.tek271.reverseProxy.model.Mapping;
import com.tek271.reverseProxy.text.UrlMapper;
import com.tek271.reverseProxy.utils.Tuple2;

public class ProxyFilter implements Filter {

  public void init(FilterConfig filterConfig) throws ServletException {
  }
  
  public void destroy() {
  }

  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) 
          throws IOException, ServletException {
    if (!isHttp(request, response)) return;
    
    Tuple2<Mapping, String> mapped = mapUrlProxyToHidden(request);
    if (mapped.isNull()) {
      chain.doFilter(request, response);
      return;
    }
    
    executeRequest(response, mapped.e1, mapped.e2, ((HttpServletRequest)request));
  }
  
  private static boolean isHttp(ServletRequest request, ServletResponse response) {
    return (request instanceof HttpServletRequest) && (response instanceof HttpServletResponse);
  }
  
  private static Tuple2<Mapping, String> mapUrlProxyToHidden(ServletRequest request) {
    String oldUrl = ((HttpServletRequest)request).getRequestURL().toString();
	String queryString = ((HttpServletRequest)request).getQueryString();  
	if (queryString != null) {
		oldUrl += "?"+queryString;
	}
    return UrlMapper.mapFullUrlProxyToHidden(oldUrl);
  }
  
  /**
   * Helper method for passing post-requests
   */
  private static HttpUriRequest createNewRequest(HttpServletRequest request, String newUrl) throws UnsupportedEncodingException{
	  String method = request.getMethod();
	  if (method.equals("POST")) {
		  List<NameValuePair> formparams = new ArrayList<NameValuePair>();
		  Enumeration<String> en = request.getParameterNames();
		  while (en.hasMoreElements()){
			  String name = en.nextElement();
			  String value = request.getParameter(name);
			  formparams.add(new BasicNameValuePair("json", value));
		  }
		  UrlEncodedFormEntity entity = new UrlEncodedFormEntity(formparams, "UTF-8");
		  HttpPost httppost = new HttpPost(newUrl);
		  httppost.setEntity(entity);
		  return httppost;
	  }
	  else if (method.equals("PUT")){
		  return new HttpPut(newUrl);
	  }
	  else {
		  return new HttpGet(newUrl);
	  }
  }
  
  private static void executeRequest(ServletResponse response, Mapping mapping, String newUrl, HttpServletRequest request) 
          throws IOException, ClientProtocolException {
	HttpClient httpclient = new DefaultHttpClient();
	
    HttpUriRequest httpRequest = createNewRequest(request, newUrl);
    HttpResponse r = httpclient.execute(httpRequest);
    HttpEntity entity = r.getEntity();
    
    ContentTranslator contentTranslator= new ContentTranslator(mapping, newUrl);
    contentTranslator.translate(entity, response);
  }

  
}

package com.wisdge.dataservice.xhr;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import com.wisdge.dataservice.exceptions.MovedException;
import com.wisdge.utils.LogUtils;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.NoHttpResponseException;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.DefaultProxyRoutePlanner;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.pool.PoolStats;
import org.apache.http.util.EntityUtils;
import org.dom4j.Element;
import com.wisdge.dataservice.exceptions.IllegalUrlException;
import com.wisdge.dataservice.exceptions.XhrException;
import com.wisdge.utils.DomUtils;

/**
 * XHR?????????????????????
 *
 * @author Kevin MOU
 */
@Slf4j
@Data
public class XHRPoolService {
	private static final String XHR_GET_LOG_HEAD = "[XHR-GET] {}";
	private static final String XHR_POST_LOG_HEAD = "[XHR-POST] {}";
	private static final String UTF_8 = "UTF-8";
	private static final String CONTENT_TYPE_JSON_UTF8 = "application/json;charset=UTF-8";

	public static final int RESULT_XML = 0;
	public static final int RESULT_JSON = 1;

	public static final String METHOD_GET = "GET";
	public static final String METHOD_POST = "POST";
	public static final String METHOD_WS_GET = "WS_GET";
	public static final String METHOD_WS_POST = "WS_POST";

	private String protocol = "TLSv1.2";

	/**
	 * ?????????????????? ??????????????????
	 */
	private int requestTimeout = 60000;
	private int connectTimeout = 30000;
	private int socketTimeout = 600000;

	/**
	 * ????????????????????????
	 */
	private int maxConnection = 300;

	/**
	 * ???????????????????????????
	 */
	private int maxPerRoute = 100;

	/**
	 * ???????????????,????????????
	 */
	private int maxRetryCount = 3;

	/**
	 * ????????????
	 */
	private ProxyConfig proxyConfig;

	@Getter(AccessLevel.NONE)
	@Setter(AccessLevel.NONE)
	private PoolingHttpClientConnectionManager connectionManager;
	@Setter(AccessLevel.NONE)
	@Getter(AccessLevel.NONE)
	private CloseableHttpClient httpClient;
	@Getter(AccessLevel.NONE)
	@Setter(AccessLevel.NONE)
	private IdleConnectionMonitorThread thread;

	/**
	 * ??????HttpClient?????????
	 */
	public CloseableHttpClient getHttpClient() {
		if (httpClient != null) return httpClient;

		try {
			Registry<ConnectionSocketFactory> registry = RegistryBuilder.<ConnectionSocketFactory>create()
					.register("http", PlainConnectionSocketFactory.getSocketFactory())
					.register("https", createSSLConnSocketFactory()).build();

			// ?????????????????????
			connectionManager = new PoolingHttpClientConnectionManager(registry);
			connectionManager.setMaxTotal(maxConnection);// ?????????????????????
			log.debug("maxConnection: " + maxConnection);
			connectionManager.setDefaultMaxPerRoute(maxPerRoute);// ?????????????????????????????????
			log.debug("maxPerRoute: " + maxPerRoute);
			log.debug("maxRetryCount: " + maxRetryCount);


			// ??????????????????????????????
			// HttpHost host = new HttpHost("account.daffy.service");//???????????????
			// connManager.setMaxPerRoute(new HttpRoute(host), 50);//?????????????????????????????????????????????50???????????????

			RequestConfig config = RequestConfig.custom()
					.setConnectionRequestTimeout(requestTimeout)// ?????????????????????????????????????????????
					.setConnectTimeout(connectTimeout)// ????????????????????????
					.setSocketTimeout(socketTimeout)// ????????????????????????
					.build();
			HttpClientBuilder clientBuilder = HttpClients.custom()
					.setConnectionManager(connectionManager)
					.setRetryHandler(httpRequestRetry())
					.setDefaultRequestConfig(config);

			// ??????httpClient??????
			if (proxyConfig != null) {
				HttpHost proxyHost = new HttpHost(proxyConfig.getHost(), proxyConfig.getPort());
				DefaultProxyRoutePlanner routePlanner = new DefaultProxyRoutePlanner(proxyHost);
				httpClient = clientBuilder.setRoutePlanner(routePlanner).build();
			} else {
				httpClient = clientBuilder.build();
			}

			thread = new IdleConnectionMonitorThread(connectionManager);
			thread.start();
			log.info("Initialize XhrPoolService succeeded");
			return httpClient;
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			return null;
		}
	}

	public PoolingHttpClientConnectionManager getConnectionManager() {
		getHttpClient();
		return connectionManager;
	}

	public PoolStats getPoolStats() {
		return getConnectionManager().getTotalStats();
	}

	/**
	 * ??????SSL??????
	 * @throws NoSuchAlgorithmException
	 * @throws KeyManagementException
	 *
	 * @throws Exception
	 */
	private SSLConnectionSocketFactory createSSLConnSocketFactory() throws NoSuchAlgorithmException, KeyManagementException {
		// ??????TrustManager
		X509TrustManager xtm = new X509TrustManager() {
			@Override
			public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
			}

			@Override
			public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
			}

			@Override
			public X509Certificate[] getAcceptedIssuers() {
				return null;
			}
		};
		SSLContext ctx = SSLContext.getInstance(protocol);
		ctx.init(null, new TrustManager[] { xtm }, null);

		return new SSLConnectionSocketFactory(ctx, NoopHostnameVerifier.INSTANCE);
	}

	/**
	 * ??????????????????????????????
	 */
	private HttpRequestRetryHandler httpRequestRetry() {
		return (exception, executionCount, context) -> {
			if (executionCount >= maxRetryCount) {
				// ????????????MAX_EXECUT_COUNT????????????
				return false;
			}
			if (exception instanceof NoHttpResponseException) {
				// ?????????????????????????????????????????????
				log.error("httpclient ?????????????????????");
				return true;
			}
			if (exception instanceof SSLHandshakeException) {
				// SSL???????????????????????????
				log.error("httpclient SSL????????????");
				return false;
			}
			if (exception instanceof InterruptedIOException) {
				// ?????????????????????
				log.error("httpclient ????????????");
				return false;
			}
			if (exception instanceof UnknownHostException) {
				// ???????????????????????????????????????
				log.error("httpclient ????????????????????????");
				return false;
			}
			if (exception instanceof ConnectTimeoutException) {
				// ??????????????????????????????
				log.error("httpclient ???????????????");
				return false;
			}
			if (exception instanceof SSLException) {
				// SSL?????????????????????
				log.error("httpclient SSL??????");
				return false;
			}

			// ??????????????????????????????????????????
			HttpClientContext clientContext = HttpClientContext.adapt(context);
			HttpRequest request = clientContext.getRequest();
			if (!(request instanceof HttpEntityEnclosingRequest)) {
				return true;
			}
			return false;
		};
	}

	/**
	 * ??????????????????????????????
	 */
	public void destroy() {
		try {
			if (thread != null)
				thread.shutdown();
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}

		if (connectionManager == null) {
			return;
		}
		// ???????????????
		connectionManager.shutdown();
		// ??????httpClient??????
		httpClient = null;
		connectionManager = null;
		log.info("XhrPoolService destroyed");
	}

	/**
	 * ???XHR????????????GET??????????????????????????????
	 *
	 * @param url
	 *            HTTP????????????
	 * @return String
	 * @throws Exception
	 */
	public String get(String url) throws Exception {
		return get(url, null);
	}

	/**
	 * ???XHR????????????GET??????????????????????????????
	 *
	 * @param url
	 *            HTTP????????????
	 * @param params
	 *            GET?????????MAP??????
	 * @return String
	 * @throws Exception
	 */
	public String get(String url, Map<String, Object> params) throws Exception {
		return get(url, params, new HashMap<>());
	}

	/**
	 * ???XHR????????????GET??????????????????????????????
	 *
	 * @param url
	 *            HTTP????????????
	 * @param params
	 *            GET?????????MAP??????
	 * @return String
	 * @throws Exception
	 */
	public String get(String url, Map<String, Object> params, Map<String, String> heads) throws Exception {
		url = makeGetMethodUrl(url, params);
		log.debug(XHR_GET_LOG_HEAD, LogUtils.forging(url));
		HttpGet httpGet = new HttpGet(url);
		if (heads != null) {
			Iterator<String> iter = heads.keySet().iterator();
			while(iter.hasNext()) {
				String name = iter.next();
				httpGet.setHeader(name, heads.get(name));
			}
		}

		CloseableHttpResponse httpResponse = getHttpClient().execute(httpGet);
		try {
			return entity2String(httpResponse);
//		} catch (MovedException e) {
//			return get(e.getMessage(), params, heads);
		} finally {
			httpResponse.close();
			httpGet.releaseConnection();
		}
	}

	/**
	 * ???XHR????????????GET??????????????????
	 * @param url
	 *            HTTP????????????
	 * @param params
	 *            GET?????????MAP??????
	 * @param handler
	 *            IResponseHandler
	 * @throws IOException
	 */
	public void get(String url, Map<String, Object> params, IResponseHandler handler) throws Exception {
		url = makeGetMethodUrl(url, params);
		log.debug(XHR_GET_LOG_HEAD, LogUtils.forging(url));
		HttpGet httpGet = new HttpGet(url);
		CloseableHttpResponse httpResponse = getHttpClient().execute(httpGet);
		try {
			handler.doHandle(httpResponse);
		} finally {
			httpResponse.close();
			httpGet.releaseConnection();
		}
	}

	public CloseableHttpResponse getResponse(String url) throws Exception {
		return getResponse(url, null);
	}
	/**
	 * ???XHR????????????GET????????????CloseableHttpResponse??????????????????????????????close
	 *
	 * @param url
	 *            HTTP????????????
	 * @param params
	 *            GET?????????MAP??????
	 * @return CloseableHttpResponse
	 * @throws Exception
	 */
	public CloseableHttpResponse getResponse(String url, Map<String, Object> params) throws Exception {
		url = makeGetMethodUrl(url, params);
		log.debug(XHR_GET_LOG_HEAD, LogUtils.forging(url));
		HttpGet httpGet = new HttpGet(url);
		return getHttpClient().execute(httpGet);
	}

	/**
	 * ???XHR????????????GET????????????byte??????
	 *
	 * @param url
	 *            HTTP????????????
	 * @return byte[]
	 * @throws Exception
	 */
	public byte[] getForBytes(String url) throws Exception {
		return getForBytes(url, null, null);
	}

	/**
	 * ???XHR????????????GET????????????byte??????
	 *
	 * @param url
	 *            HTTP????????????
	 * @param params
	 *            GET?????????MAP??????
	 * @return byte[]
	 * @throws Exception
	 */
	public byte[] getForBytes(String url, Map<String, Object> params) throws Exception {
		return getForBytes(url, params, null);
	}

	/**
	 * ???XHR????????????GET????????????byte??????
	 *
	 * @param url
	 *            HTTP????????????
	 * @param params
	 *            GET?????????MAP??????
	 * @return byte[]
	 * @throws Exception
	 */
	public byte[] getForBytes(String url, Map<String, Object> params, Map<String, String> headers) throws Exception {
		url = makeGetMethodUrl(url, params);
		log.debug(XHR_GET_LOG_HEAD, LogUtils.forging(url));
		HttpGet httpGet = new HttpGet(url);
		if (headers != null) {
			Iterator<String> iter = headers.keySet().iterator();
			while(iter.hasNext()) {
				String name = iter.next();
				httpGet.setHeader(name, headers.get(name));
			}
		}
		CloseableHttpResponse httpResponse = getHttpClient().execute(httpGet);
		try {
			return entity2Bytes(httpResponse);
		} finally {
			httpResponse.close();
			httpGet.releaseConnection();
		}
	}

	/**
     * ??????response header???Content-Disposition??????filename???
     * @param response CloseableHttpResponse
     * @return String
     */
    public String getFileName(CloseableHttpResponse response) {
        Header contentHeader = response.getFirstHeader("Content-Disposition");
        String filename = null;
        if (contentHeader != null) {
            HeaderElement[] values = contentHeader.getElements();
            if (values.length == 1) {
                NameValuePair param = values[0].getParameterByName("filename");
                if (param != null) {
                    filename = param.getValue();
                }
            }
        }
        return filename;
    }

	private String makeGetMethodUrl(String url, Map<String, Object> params) throws UnsupportedEncodingException {
		url = url.trim().replaceAll("\n", "");
		int sp = url.indexOf('?');
		if (sp == -1) {
			url += "?";
		}

		if (params != null) {
			String key;
			String value;
			Iterator<String> iter = params.keySet().iterator();
			while (iter.hasNext()) {
				key = iter.next();
				value = params.get(key).toString();
				if (! url.endsWith("?")) {
					url += "&";
				}
				url += key + "=" + encodeStringFromObject(value);
			}
		}
		return url;
	}

	private String encodeStringFromObject(Object object) throws UnsupportedEncodingException {
		if (object == null) {
			return "";
		} else {
			return URLEncoder.encode(object.toString(), UTF_8);
		}
	}

	/**
	 * ???XHR?????????POST???????????????????????????????????????
	 *
	 * @param url
	 *            HTTP????????????
	 * @return String
	 * @throws Exception
	 */
	public String post(String url) throws Exception {
		return post(url, new HashMap<>());
	}

	/**
	 * ???XHR?????????POST???????????????????????????????????????
	 *
	 * @param url
	 *            HTTP????????????
	 * @param params
	 *            POST????????????????????????MAP??????
	 * @return String
	 * @throws Exception
	 */
	public String post(String url, Map<String, Object> params) throws Exception {
		return post(url, params, new HashMap<>());
	}

	public String post(String url, Map<String, Object> params, Map<String, String> headers) throws Exception {
		url = url.trim().replaceAll("\n", "");
		List<NameValuePair> nvps = new ArrayList<NameValuePair>();

		// add parameters extend
		if (params != null) {
			Iterator<String> iter = params.keySet().iterator();
			while (iter.hasNext()) {
				String key = iter.next();
				Object value = params.get(key);
				nvps.add(new BasicNameValuePair(key, value == null ? "" : value.toString()));
			}
		}

		log.debug(XHR_POST_LOG_HEAD, LogUtils.forging(url));
		HttpPost httpPost = new HttpPost(url);
		httpPost.setEntity(new UrlEncodedFormEntity(nvps, UTF_8));
		if (headers != null) {
			Iterator<String> iter = headers.keySet().iterator();
			while(iter.hasNext()) {
				String name = iter.next();
				httpPost.setHeader(name, headers.get(name));
			}
		}

		CloseableHttpResponse httpResponse = getHttpClient().execute(httpPost);
		try {
			return entity2String(httpResponse);
		} finally {
			httpResponse.close();
			httpPost.releaseConnection();
		}
	}

	public String post(HttpPost httpPost) throws Exception {
		CloseableHttpResponse httpResponse = getHttpClient().execute(httpPost);
		try {
			return entity2String(httpResponse);
		} finally {
			httpResponse.close();
			httpPost.releaseConnection();
		}
	}

	/**
	 * ???XHR????????????POST??????????????????
	 * @param url
	 *            HTTP????????????
	 * @param params
	 *            GET?????????MAP??????
	 * @param handler
	 *            IResponseHandler
	 * @throws Exception
	 */
	public void post(String url, Map<String, Object> params, Map<String, String> headers, IResponseHandler handler) throws Exception {
		url = url.trim().replaceAll("\n", "");
		List<NameValuePair> nvps = new ArrayList<NameValuePair>();

		// add parameters extend
		if (params != null) {
			Iterator<String> iter = params.keySet().iterator();
			while (iter.hasNext()) {
				String key = iter.next();
				Object value = params.get(key);
				nvps.add(new BasicNameValuePair(key, value == null ? "" : value.toString()));
			}
		}

		log.debug(XHR_POST_LOG_HEAD, LogUtils.forging(url));
		HttpPost httpPost = new HttpPost(url);
		httpPost.setEntity(new UrlEncodedFormEntity(nvps, UTF_8));
		if (headers != null) {
			Iterator<String> iter = headers.keySet().iterator();
			while(iter.hasNext()) {
				String name = iter.next();
				httpPost.setHeader(name, headers.get(name));
			}
		}

		CloseableHttpResponse httpResponse = getHttpClient().execute(httpPost);
		try {
			handler.doHandle(httpResponse);
		} finally {
			httpResponse.close();
			httpPost.releaseConnection();
		}
	}

	public byte[] postForBytes(String url, Map<String, Object> params) throws Exception {
		return postForBytes(url, params, null);
	}

	/**
	 * ???XHR?????????POST????????????byte??????
	 *
	 * @param url
	 *            HTTP????????????
	 * @param params
	 *            POST????????????????????????MAP??????
	 * @param headers
	 *            POST???????????????????????????heads
	 * @return byte[]
	 * @throws Exception
	 */
	public byte[] postForBytes(String url, Map<String, Object> params, Map<String, String> headers) throws Exception {
		url = url.trim().replaceAll("\n", "");
		List<NameValuePair> nvps = new ArrayList<NameValuePair>();

		// add parameters extend
		if (params != null) {
			Iterator<String> iter = params.keySet().iterator();
			while (iter.hasNext()) {
				String key = iter.next();
				Object value = params.get(key);
				nvps.add(new BasicNameValuePair(key, value == null ? "" : value.toString()));
			}
		}

		log.debug(XHR_POST_LOG_HEAD, LogUtils.forging(url));
		HttpPost httpPost = new HttpPost(url);
		httpPost.setEntity(new UrlEncodedFormEntity(nvps, UTF_8));
		if (headers != null) {
			Iterator<String> iter = headers.keySet().iterator();
			while(iter.hasNext()) {
				String name = iter.next();
				httpPost.setHeader(name, headers.get(name));
			}
		}
		CloseableHttpResponse httpResponse = getHttpClient().execute(httpPost);
		try {
			return entity2Bytes(httpResponse);
		} finally {
			httpResponse.close();
			httpPost.releaseConnection();
		}
	}

	/**
	 * ???XHR?????????POST????????????byte??????
	 *
	 * @param url
	 *            String HTTP????????????
	 * @param entity
	 *            HttpEntity POST????????????????????????MAP??????
	 * @return byte[]
	 * @throws Exception
	 */
	public byte[] postForBytes(String url, HttpEntity entity,  Map<String, String> headers) throws Exception {
		url = url.trim().replaceAll("\n", "");
		HttpPost httpPost = new HttpPost(url);
		httpPost.setEntity(entity);
		if (headers != null) {
			Iterator<String> iter = headers.keySet().iterator();
			while(iter.hasNext()) {
				String name = iter.next();
				httpPost.setHeader(name, headers.get(name));
			}
		}
		CloseableHttpResponse httpResponse = getHttpClient().execute(httpPost);
		try {
			return entity2Bytes(httpResponse);
		} finally {
			httpResponse.close();
			httpPost.releaseConnection();
		}
	}

	/**
	 * ???XHR?????????POST????????????byte??????
	 *
	 * @param url
	 *            String HTTP????????????
	 * @param entity
	 *            HttpEntity POST????????????????????????MAP??????
	 * @return byte[]
	 * @throws Exception
	 */
	public byte[] postForBytes(String url, HttpEntity entity) throws Exception {
		return postForBytes(url, entity, null);
	}

	/**
	 * ???XHR?????????POST??????????????????
	 *
	 * @param url
	 *            HTTP????????????
	 * @param params
	 *            POST????????????????????????MAP??????
	 * @return FileBean
	 * @throws Exception
	 */
	public FileBean postForFile(String url, Map<String, Object> params) throws Exception {
		url = url.trim().replaceAll("\n", "");
		List<NameValuePair> nvps = new ArrayList<NameValuePair>();

		// add parameters extend
		if (params != null) {
			Iterator<String> iter = params.keySet().iterator();
			while (iter.hasNext()) {
				String key = iter.next();
				Object value = params.get(key);
				nvps.add(new BasicNameValuePair(key, value == null ? "" : value.toString()));
			}
		}

		log.debug(XHR_POST_LOG_HEAD, LogUtils.forging(url));
		HttpPost httpPost = new HttpPost(url);
		httpPost.setEntity(new UrlEncodedFormEntity(nvps, UTF_8));
		CloseableHttpResponse httpResponse = getHttpClient().execute(httpPost);
		try {
			Header[] cds = httpResponse.getHeaders("Content-disposition");
			if (cds.length == 0)
				throw new IllegalUrlException(500, "???????????????Content-disposition");
			HeaderElement[] elements = cds[0].getElements();
			if (elements.length == 0)
				throw new IllegalUrlException(500, "???????????????HeaderElement");

			String filename = elements[0].getParameterByName("filename").getValue();
			byte[] data = entity2Bytes(httpResponse);
			return new FileBean(filename, data);
		} finally {
			httpResponse.close();
			httpPost.releaseConnection();
		}
	}

	/**
	 * ???XHR????????????POST JSON????????????????????????????????????
	 *
	 * @param url
	 *            HTTP????????????
	 * @param jsonBody
	 *            POST????????????JSON??????
	 * @return String
	 * @throws Exception
	 */
	public String post(String url, String jsonBody) throws Exception {
		return post(url, jsonBody, CONTENT_TYPE_JSON_UTF8);
	}

	public String post(String url, String jsonBody, String contentType) throws Exception {
		return post(url, jsonBody, contentType, new HashMap<>());
	}

	public String post(String url, String jsonBody, String contentType, Map<String, String> headers) throws Exception {
		headers.put("Content-type", contentType);
		return post(url, jsonBody, headers);
	}

	public String post(String url, String jsonBody, Map<String, String> headers) throws Exception {
		log.debug(XHR_POST_LOG_HEAD, LogUtils.forging(url));
		HttpPost httpPost = new HttpPost(url);
		StringEntity se = new StringEntity(jsonBody, UTF_8);
		if (headers != null) {
			Iterator<String> iter = headers.keySet().iterator();
			while(iter.hasNext()) {
				String name = iter.next();
				httpPost.setHeader(name, headers.get(name));
			}
		}
		httpPost.setEntity(se);

		//log.debug("doPostPayload: " + jsonBody);
		CloseableHttpResponse httpResponse = getHttpClient().execute(httpPost);
		try {
//			int statusCode = httpResponse.getStatusLine().getStatusCode();
//			if (statusCode == HttpStatus.SC_MOVED_TEMPORARILY || statusCode == HttpStatus.SC_MOVED_PERMANENTLY) {
//				Header header = httpResponse.getFirstHeader("location"); // ??????????????????????????? HTTP-HEAD ??????
//	            String newUri = header.getValue(); // ?????????????????????????????????????????????????????????????????????????????????????????????????????????
//	            log.debug("HttpRequest 302 return: {}", newUri);
//	            return post(newUri, jsonBody, heads);
//			}
			return entity2String(httpResponse);
		} finally {
			httpResponse.close();
			httpPost.releaseConnection();
		}
	}

	/**
	 * ???XHR????????????POST??????????????????
	 * @param url
	 *            HTTP????????????
	 * @param postBody
	 *            POST????????????JSON??????
	 * @param handler
	 *            IResponseHandler
	 * @throws Exception
	 */
	public void post(String url, String postBody, IResponseHandler handler) throws Exception {
		log.debug(XHR_POST_LOG_HEAD, LogUtils.forging(url));
		HttpPost httpPost = new HttpPost(url);
		StringEntity se = new StringEntity(postBody, UTF_8);
		httpPost.setHeader("Content-Type", CONTENT_TYPE_JSON_UTF8);
		httpPost.setEntity(se);

		//log.debug("doPostPayload: " + postBody);
		CloseableHttpResponse httpResponse = getHttpClient().execute(httpPost);
		try {
			handler.doHandle(httpResponse);
		} finally {
			httpResponse.close();
			httpPost.releaseConnection();
		}
	}

	public CloseableHttpResponse postResponse(String url) throws Exception {
		return postResponse(url, new HashMap<String, Object>());
	}

	/**
	 * ???XHR?????????POST????????????CloseableHttpResponse??????????????????close
	 *
	 * @param url
	 *            HTTP????????????
	 * @param params
	 *            POST????????????????????????MAP??????
	 * @return CloseableHttpResponse
	 * @throws Exception
	 */
	public CloseableHttpResponse postResponse(String url, Map<String, Object> params) throws Exception {
		return postResponse(url, params, null);
	}

	/**
	 * ???XHR?????????POST????????????CloseableHttpResponse??????????????????close
	 *
	 * @param url
	 *            HTTP????????????
	 * @param params
	 *            POST????????????????????????MAP??????
	 * @param headers
	 * 			  Header???
	 * @return CloseableHttpResponse
	 * @throws Exception
	 */
	public CloseableHttpResponse postResponse(String url, Map<String, Object> params, Map<String, String> headers) throws Exception {
		url = url.trim().replaceAll("\n", "");
		List<NameValuePair> nvps = new ArrayList<NameValuePair>();

		// add parameters extend
		if (params != null) {
			Iterator<String> iter = params.keySet().iterator();
			while (iter.hasNext()) {
				String key = iter.next();
				Object value = params.get(key);
				nvps.add(new BasicNameValuePair(key, value == null ? "" : value.toString()));
			}
		}

		log.debug(XHR_POST_LOG_HEAD, LogUtils.forging(url));
		HttpPost httpPost = new HttpPost(url);
		if (headers != null) {
			Iterator<String> iter = headers.keySet().iterator();
			while(iter.hasNext()) {
				String name = iter.next();
				httpPost.setHeader(name, headers.get(name));
			}
		}
		httpPost.setEntity(new UrlEncodedFormEntity(nvps, UTF_8));
		return getHttpClient().execute(httpPost);
	}

	/**
	 * ???XHR????????????POST??????????????????
	 * @param url
	 *            HTTP????????????
	 * @param postBody
	 *            POST????????????JSON??????
	 * @return CloseableHttpResponse
	 * @throws Exception
	 */
	public CloseableHttpResponse postResponse(String url, String postBody) throws Exception {
		return postResponse(url, postBody, null);
	}

	/**
	 * ???XHR????????????POST??????????????????
	 * @param url
	 *            HTTP????????????
	 * @param postBody
	 *            POST????????????JSON??????
	 * @param headers
	 * 			  Header???
	 * @return CloseableHttpResponse
	 * @throws Exception
	 */
	public CloseableHttpResponse postResponse(String url, String postBody, Map<String, String> headers) throws Exception {
		log.debug(XHR_POST_LOG_HEAD, LogUtils.forging(url));
		HttpPost httpPost = new HttpPost(url);
		StringEntity se = new StringEntity(postBody, UTF_8);
		httpPost.setHeader("Content-Type", CONTENT_TYPE_JSON_UTF8);
		if (headers != null) {
			Iterator<String> iter = headers.keySet().iterator();
			while(iter.hasNext()) {
				String name = iter.next();
				httpPost.setHeader(name, headers.get(name));
			}
		}
		httpPost.setEntity(se);
		return getHttpClient().execute(httpPost);
	}

	public String postEntity(String url, HttpEntity entity) throws Exception {
		return postEntity(url, entity, null);
	}

	public String postEntity(String url, HttpEntity entity, Map<String, String> headers) throws Exception {
		HttpPost httpPost = new HttpPost(url);
		httpPost.setEntity(entity);
		if (headers != null) {
			Iterator<String> iter = headers.keySet().iterator();
			while(iter.hasNext()) {
				String name = iter.next();
				httpPost.setHeader(name, headers.get(name));
			}
		}
		CloseableHttpResponse httpResponse = getHttpClient().execute(httpPost);
		try {
			return entity2String(httpResponse);
		} finally {
			httpResponse.close();
			httpPost.releaseConnection();
		}
	}

	/**
	 * ???XHR????????????POST XML????????????????????????????????????
	 *
	 * @param url
	 *            HTTP????????????
	 * @param xmlBody
	 *            POST????????????XML??????
	 * @return String
	 * @throws Exception
	 */
	public String postXML(String url, String xmlBody, Map<String, String> headers) throws Exception {
		return post(url, xmlBody, "application/xml; charset=UTF-8", headers);
	}

	public String postXSD(String url, String xmlBody, Map<String, String> headers) throws Exception {
		return post(url, xmlBody, "text/xml; charset=UTF-8", headers);
	}

	public String postSOAP(String url, String xmlBody, Map<String, String> headers) throws Exception {
		return post(url, xmlBody, "application/soap+xml; charset=UTF-8", headers);
	}

	public byte[] postForBytes(String url, String jsonBody) throws Exception {
		return postForBytes(url, jsonBody, null);
	}

	public byte[] postForBytes(String url, String jsonBody, Map<String, String> heads) throws Exception {
		log.debug(XHR_POST_LOG_HEAD, LogUtils.forging(url));
		HttpPost httpPost = new HttpPost(url);
		StringEntity se = new StringEntity(jsonBody, UTF_8);
		httpPost.setHeader("Content-Type", CONTENT_TYPE_JSON_UTF8);
		httpPost.setEntity(se);

		log.debug("doPostPayload: " + jsonBody);
		if (heads != null) {
			Iterator<String> iter = heads.keySet().iterator();
			while(iter.hasNext()) {
				String name = iter.next();
				httpPost.setHeader(name, heads.get(name));
			}
		}
		CloseableHttpResponse httpResponse = getHttpClient().execute(httpPost);
		try {
			log.debug("queryResponse: " + httpResponse.getStatusLine().getStatusCode());
			return entity2Bytes(httpResponse);
		} finally {
			httpResponse.close();
			httpPost.releaseConnection();
		}
	}

	/**
	 * ???XHR????????????delete??????
	 *
	 * @param url
	 *            HTTP????????????
	 */
	public String delete(String url) throws Exception {
		return delete(url, new HashMap<>());
	}

	/**
	 * ???XHR?????????delete???????????????????????????????????????
	 *
	 * @param url
	 *            HTTP????????????
	 * @param headers
	 *            delete??????????????????headers??????MAP??????
	 */
	public String delete(String url, Map<String, Object> headers) throws Exception {
		HttpDelete httpDelete = new HttpDelete(url);
		url = url.trim().replaceAll("\n", "");
		if (headers != null) {
			Iterator<String> iter = headers.keySet().iterator();
			while(iter.hasNext()) {
				String name = iter.next();
				httpDelete.setHeader(name, (String) headers.get(name));
			}
		}
		log.debug(XHR_POST_LOG_HEAD, LogUtils.forging(url));
		CloseableHttpResponse httpResponse = getHttpClient().execute(httpDelete);
		try {
			return entity2String(httpResponse);
		} finally {
			httpResponse.close();
			httpDelete.releaseConnection();
		}
	}

	public String entity2String(HttpResponse httpResponse) throws Exception {
		StatusLine statusLine = httpResponse.getStatusLine();
		int statusCode = statusLine.getStatusCode();
		if (statusCode != HttpStatus.SC_OK) {
			if (statusCode == HttpStatus.SC_MOVED_TEMPORARILY || statusCode == HttpStatus.SC_MOVED_PERMANENTLY) {
				Header header = httpResponse.getFirstHeader("location"); // ??????????????????????????? HTTP-HEAD ??????
				throw new MovedException(header.getValue()); // ??????????????????????????????????????????????????????????????????????????????????????????????????????
			} else {
				String payload = "";
				HttpEntity responseEntity = httpResponse.getEntity();
				if (responseEntity != null)
					payload = EntityUtils.toString(responseEntity, UTF_8);
				throw new XhrException(statusCode, statusLine.getReasonPhrase(), payload);
			}
		}

		HttpEntity responseEntity = httpResponse.getEntity();
		return EntityUtils.toString(responseEntity, UTF_8);
	}

	public byte[] entity2Bytes(HttpResponse httpResponse) throws Exception {
		StatusLine statusLine = httpResponse.getStatusLine();
		int statusCode = statusLine.getStatusCode();
		if (statusCode != HttpStatus.SC_OK) {
			String payload = "";
			try {
				if (statusCode == HttpStatus.SC_MOVED_TEMPORARILY || statusCode == HttpStatus.SC_MOVED_PERMANENTLY) {
					Header header = httpResponse.getFirstHeader("location"); // ??????????????????????????? HTTP-HEAD ??????
					payload = header.getValue(); // ?????????????????????????????????????????????????????????????????????????????????????????????????????????
				} else {
					HttpEntity responseEntity = httpResponse.getEntity();
					if (responseEntity != null)
						payload = EntityUtils.toString(responseEntity, UTF_8);
				}
			} catch(Exception e) {}
			throw new XhrException(statusCode, statusLine.getReasonPhrase(), payload);
		}

		InputStream is = httpResponse.getEntity().getContent();
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			int l = -1;
			byte[] tmp = new byte[1024];
			while ((l = is.read(tmp)) != -1) {
				baos.write(tmp, 0, l);
			}
			baos.flush();
			byte[] buffer = baos.toByteArray();
			baos.close();
			return buffer;
		} finally {
			is.close();
		}
	}

	/**
	 * ????????????HttpRequest
	 *
	 * @param request
	 *            HttpUriRequest??????
	 * @return String
	 * @throws Exception
	 */
	public String execute(HttpUriRequest request) throws Exception {
		CloseableHttpResponse httpResponse = getHttpClient().execute(request);
		try {
			return entity2String(httpResponse);
		} finally {
			httpResponse.close();
		}
	}

	/**
	 * ???????????????byte??????
	 *
	 * @param url
	 *            HTTP????????????
	 * @param params
	 *            POST??????????????????
	 * @param method
	 *            ???????????????GET??????POST
	 * @return byte[]
	 * @throws Exception
	 */
	public byte[] readBytes(String url, Map<String, Object> params, String method) throws Exception {
		if (method.equalsIgnoreCase(METHOD_GET)) {
			return getForBytes(url, params);
		} else {
			return postForBytes(url, params);
		}
	}

	public Element soap(String url, String methodName, String namespace, Map<String, Object> parameterMap) throws Exception {
		String xmlBody = buildWebServiceRequestData(methodName, namespace, parameterMap);
		String strXML = postSOAP(url, xmlBody, null);
		Element bodyElement = DomUtils.parser(strXML).getRootElement().element("Body");
		Element responseElement = bodyElement.element(methodName + "Response");
		return responseElement.element(methodName + "Result");
	}

	public String buildWebServiceRequestData(String methodName, String namespace, Map<String, Object> parameterMap) {
		StringBuilder soapRequestData = new StringBuilder();
		soapRequestData.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
		soapRequestData.append("<soap:Envelope xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
				+ " xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\""
				+ " xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\">");
		soapRequestData.append("<soap:Body>");
		soapRequestData.append("<" + methodName + " xmlns=\"" + namespace + "\">");

		if (parameterMap != null) {
			Set<String> nameSet = parameterMap.keySet();
			for (String name : nameSet) {
				soapRequestData.append("<" + name + ">" + parameterMap.get(name) + "</" + name + ">");
			}
		}

		soapRequestData.append("</" + methodName + ">");
		soapRequestData.append("</soap:Body>");
		soapRequestData.append("</soap:Envelope>");
		return soapRequestData.toString();
	}
}

class IdleConnectionMonitorThread extends Thread {
	private final HttpClientConnectionManager connMgr;
	private volatile boolean shutdown;

	public IdleConnectionMonitorThread(HttpClientConnectionManager connMgr) {
		super();
		this.connMgr = connMgr;
	}

	@Override
	public void run() {
		try {
			while (!shutdown) {
				synchronized (this) {
					wait(5000);
					// Close expired connections
					connMgr.closeExpiredConnections();
					// Optionally, close connections
					// that have been idle longer than 30 sec
					connMgr.closeIdleConnections(30, TimeUnit.SECONDS);
				}
			}
		} catch (Exception e) {
			// terminate
		}
	}

	public void shutdown() {
		shutdown = true;
		synchronized (this) {
			notifyAll();
		}
	}
}

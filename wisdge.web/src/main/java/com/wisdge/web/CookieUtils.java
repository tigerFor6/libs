package com.wisdge.web;

import java.io.*;
import javax.servlet.http.*;
import com.wisdge.utils.StringUtils;

/**
 * Utilities for working with cookies
 * Copyright(c) wisdge.com 2003
 * 
 * @author Kevin MOU
 * @version 1.0.1
 *******************************************************************************/
public class CookieUtils {

	private static final int COOKIE_LIFE = 5 * 365 * 24 * 60 * 60;
	private static final int MAX_COOKIE_PAYLOAD = 4096 - "wset01=".length() - "81920<".length() - 1;

	/**
	 * @return null or String
	 */
	public static String getCookieValue(String name, HttpServletRequest request) {
		String ret = null;
		Cookie[] cookies = request.getCookies();
		if (cookies != null) {
			for (int i = 0; i < cookies.length; i++) {
				if (name.equals(cookies[i].getName())) {
					ret = cookies[i].getValue();
					break;
				}
			}
		}
		return ret;
	}

	/**
	 * 取得cookie值，如果为null则返回默认值
	 * 
	 * @param name
	 *            cookie名称
	 * @param defaultValue
	 *            默认值
	 * @param request
	 * @return cookie值
	 */
	public static String getCookieValue(String name, String defaultValue, HttpServletRequest request) {
		String value = getCookieValue(name, request);
		return value != null ? value : defaultValue;
	}

	public static void setCookieValue(String name, String value, HttpServletResponse response) {
		setCookieValue(name, value, COOKIE_LIFE, response);
	}

	public static void setCookieValue(String uri, String name, String value, HttpServletResponse response) {
		setCookieValue(uri, name, value, COOKIE_LIFE, response);
	}

	public static void setCookieValue(String name, String value, int expiry, HttpServletResponse response) {
		Cookie cookie = new Cookie(name, value);
		cookie.setMaxAge(COOKIE_LIFE);
		response.addCookie(cookie);
	}

	public static void setCookieValue(String uri, String name, String value, int expiry, HttpServletResponse response) {
		Cookie cookie = new Cookie(name, value);
		cookie.setPath(uri);
		cookie.setMaxAge(COOKIE_LIFE);
		response.addCookie(cookie);
	}

	public static String getString(String name, String defaultValue, HttpServletRequest request) {
		return getCookieValue(name, defaultValue, request);
	}
	
	public static int getInt(String name, int defaultValue, HttpServletRequest request) {
		return StringUtils.getInt(getCookieValue(name, request), defaultValue);
	}
	
	public static boolean getBoolean(String name, boolean defaultValue, HttpServletRequest request) {
		return StringUtils.getBoolean(getCookieValue(name, request), defaultValue);
	}
	
	public static long getLong(String name, long defaultValue, HttpServletRequest request) {
		return StringUtils.getLong(getCookieValue(name, request), defaultValue);
	}
	
	public static void deleteCookie(String name, HttpServletResponse response) {
		Cookie cookie = new Cookie(name, "");
		cookie.setMaxAge(0);
		response.addCookie(cookie);
	}

	/**
	 * Saves string in multiple browser cookies. Cookies can store limited length string. This method will attempt to split string among multiple cookies. The
	 * following cookies will be set name1=length<substing1 name2=substrging2 ... name_x=substring_x
	 * 
	 * @param data
	 *            a string containing legal characters for cookie value
	 * @throws IOException
	 *             when data is too long.
	 */
	public static void saveString(String name, String data, int maxCookies, HttpServletRequest request, HttpServletResponse response) throws IOException {
		int len = data.length();
		int n = len / MAX_COOKIE_PAYLOAD;
		if (n > maxCookies) {
			throw new IOException("Too may cookies required to store data.");
		}
		for (int i = 1; i <= n; i++) {
			if (i == 1) {
				setCookieValue(name + "1", len + "<" + data.substring(0, MAX_COOKIE_PAYLOAD), response);
			} else {
				setCookieValue(name + i, data.substring(MAX_COOKIE_PAYLOAD * (i - 1), MAX_COOKIE_PAYLOAD * i), response);
			}
		}
		if (len % MAX_COOKIE_PAYLOAD > 0) {
			if (n == 0) {
				setCookieValue(name + "1", len + "<" + data.substring(0, len), response);
			} else {
				setCookieValue(name + (n + 1), data.substring(MAX_COOKIE_PAYLOAD * n, len), response);
			}
		}
		// if using less cookies than maximum, delete not needed cookies from last time
		for (int i = n + 1; i <= maxCookies; i++) {
			if (i == n + 1 && len % MAX_COOKIE_PAYLOAD > 0) {
				continue;
			}
			if (getCookieValue(name + i, request) != null) {
				deleteCookie(name + i, response);
			} else {
				break;
			}
		}
	}

	/**
	 * @return null or String
	 */
	public static String restoreString(String name, HttpServletRequest request) {
		String value1 = CookieUtils.getCookieValue(name + "1", request);
		if (value1 == null) {
			// no cookie
			return null;
		}
		String lengthAndSubstring1[] = value1.split("<");
		if (lengthAndSubstring1.length < 2) {
			return null;
		}
		int len = 0;
		try {
			len = Integer.parseInt(lengthAndSubstring1[0]);
		} catch (NumberFormatException nfe) {
			return null;
		}
		if (len <= 0) {
			return null;
		}
		StringBuffer data = new StringBuffer(len);
		data.append(lengthAndSubstring1[1]);
		int n = len / MAX_COOKIE_PAYLOAD;
		for (int i = 2; i <= n; i++) {
			String substring = CookieUtils.getCookieValue(name + i, request);
			if (substring == null) {
				return null;
			}
			data.append(substring);
		}
		if (len % MAX_COOKIE_PAYLOAD > 0 && n > 0) {
			String substring = CookieUtils.getCookieValue(name + (n + 1), request);
			if (substring == null) {
				return null;
			}
			data.append(substring);
		}
		if (data.length() != len) {
			return null;
		}
		return data.toString();
	}
}

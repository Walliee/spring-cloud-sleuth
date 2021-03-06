/*
 * Copyright 2013-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.sleuth.instrument.messaging;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import brave.propagation.Propagation;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.messaging.support.NativeMessageHeaderAccessor;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.StringUtils;

/**
 * This always sets native headers in defence of STOMP issues discussed <a href=
 * "https://github.com/spring-cloud/spring-cloud-sleuth/issues/716#issuecomment-337523705">here</a>.
 *
 * @author Marcin Grzejszczak
 */
enum MessageHeaderPropagation
		implements Propagation.Setter<MessageHeaderAccessor, String>,
		Propagation.Getter<MessageHeaderAccessor, String> {

	INSTANCE;

	private static final Log log = LogFactory.getLog(MessageHeaderPropagation.class);

	private static final Map<String, String> LEGACY_HEADER_MAPPING = new HashMap<>();

	private static final String TRACE_ID_NAME = "X-B3-TraceId";

	private static final String SPAN_ID_NAME = "X-B3-SpanId";

	private static final String PARENT_SPAN_ID_NAME = "X-B3-ParentSpanId";

	private static final String SAMPLED_NAME = "X-B3-Sampled";

	private static final String FLAGS_NAME = "X-B3-Flags";

	static {
		LEGACY_HEADER_MAPPING.put(TRACE_ID_NAME, TraceMessageHeaders.TRACE_ID_NAME);
		LEGACY_HEADER_MAPPING.put(SPAN_ID_NAME, TraceMessageHeaders.SPAN_ID_NAME);
		LEGACY_HEADER_MAPPING.put(PARENT_SPAN_ID_NAME,
				TraceMessageHeaders.PARENT_ID_NAME);
		LEGACY_HEADER_MAPPING.put(SAMPLED_NAME, TraceMessageHeaders.SAMPLED_NAME);
		LEGACY_HEADER_MAPPING.put(FLAGS_NAME, TraceMessageHeaders.SPAN_FLAGS_NAME);
	}

	static Map<String, ?> propagationHeaders(Map<String, ?> headers,
			List<String> propagationHeaders) {
		Map<String, Object> headersToCopy = new HashMap<>();
		for (Map.Entry<String, ?> entry : headers.entrySet()) {
			if (propagationHeaders.contains(entry.getKey())) {
				headersToCopy.put(entry.getKey(), entry.getValue());
			}
		}
		return headersToCopy;
	}

	static void removeAnyTraceHeaders(MessageHeaderAccessor accessor,
			List<String> keysToRemove) {
		for (String keyToRemove : keysToRemove) {
			accessor.removeHeader(keyToRemove);
			if (accessor instanceof NativeMessageHeaderAccessor) {
				NativeMessageHeaderAccessor nativeAccessor = (NativeMessageHeaderAccessor) accessor;
				nativeAccessor.removeNativeHeader(keyToRemove);
			}
			else {
				Object nativeHeaders = accessor
						.getHeader(NativeMessageHeaderAccessor.NATIVE_HEADERS);
				if (nativeHeaders instanceof Map) {
					((Map) nativeHeaders).remove(keyToRemove);
				}
			}
		}
	}

	@Override
	public void put(MessageHeaderAccessor accessor, String key, String value) {
		try {
			doPut(accessor, key, value);
		}
		catch (Exception ex) {
			if (log.isDebugEnabled()) {
				log.debug("An exception happened when we tried to retrieve the [" + key
						+ "] from message", ex);
			}
		}
		String legacyKey = LEGACY_HEADER_MAPPING.get(key);
		if (legacyKey != null) {
			doPut(accessor, legacyKey, value);
		}
	}

	private void doPut(MessageHeaderAccessor accessor, String key, String value) {
		accessor.setHeader(key, value);
		if (accessor instanceof NativeMessageHeaderAccessor) {
			NativeMessageHeaderAccessor nativeAccessor = (NativeMessageHeaderAccessor) accessor;
			nativeAccessor.setNativeHeader(key, value);
		}
		else {
			Object nativeHeaders = accessor
					.getHeader(NativeMessageHeaderAccessor.NATIVE_HEADERS);
			if (nativeHeaders == null) {
				nativeHeaders = new LinkedMultiValueMap<>();
				accessor.setHeader(NativeMessageHeaderAccessor.NATIVE_HEADERS,
						nativeHeaders);
			}
			if (nativeHeaders instanceof Map<?, ?>) {
				((Map) nativeHeaders).put(key, Collections.singletonList(value));
			}
		}
	}

	@Override
	public String get(MessageHeaderAccessor accessor, String key) {
		try {
			String value = doGet(accessor, key);
			if (StringUtils.hasText(value)) {
				return value;
			}
		}
		catch (Exception ex) {
			if (log.isDebugEnabled()) {
				log.debug("An exception happened when we tried to retrieve the [" + key
						+ "] from message", ex);
			}
		}
		return legacyValue(accessor, key);
	}

	private String legacyValue(MessageHeaderAccessor accessor, String key) {
		String legacyKey = LEGACY_HEADER_MAPPING.get(key);
		if (legacyKey != null) {
			return doGet(accessor, legacyKey);
		}
		return null;
	}

	private String doGet(MessageHeaderAccessor accessor, String key) {
		if (accessor instanceof NativeMessageHeaderAccessor) {
			NativeMessageHeaderAccessor nativeAccessor = (NativeMessageHeaderAccessor) accessor;
			String result = nativeAccessor.getFirstNativeHeader(key);
			if (result != null) {
				return result;
			}
		}
		else {
			Object nativeHeaders = accessor
					.getHeader(NativeMessageHeaderAccessor.NATIVE_HEADERS);
			if (nativeHeaders instanceof Map) {
				Object result = ((Map) nativeHeaders).get(key);
				if (result instanceof List && !((List) result).isEmpty()) {
					return String.valueOf(((List) result).get(0));
				}
			}
		}
		Object result = accessor.getHeader(key);
		if (result != null) {
			if (result instanceof byte[]) {
				return new String((byte[]) result, StandardCharsets.UTF_8);
			}
			return result.toString();
		}
		return null;
	}

	@Override
	public String toString() {
		return "MessageHeaderPropagation{}";
	}

}

// ------------------------------------------------------------------------------
// Copyright (c) 2017 Microsoft Corporation
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sub-license, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
// THE SOFTWARE.
// ------------------------------------------------------------------------------

package com.microsoft.graph.http;

import com.google.common.io.ByteStreams;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import com.microsoft.graph.core.ClientException;
import com.microsoft.graph.logger.ILogger;
import com.microsoft.graph.logger.LoggerLevel;
import com.microsoft.graph.options.HeaderOption;
import com.microsoft.graph.serializer.ISerializer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.Map.Entry;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import okhttp3.Headers;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * An exception from the Graph service
 */
public class GraphServiceException extends ClientException {
    private static final long serialVersionUID = -7416427229421064119L;

    /**
     * New line delimiter
     */
    protected static final char NEW_LINE = '\n';

    /**
     * How truncated values are shown
     */
    protected static final String TRUNCATION_MARKER = "[...]";

    /**
     * The maximum length for a single line string when trying to be brief
     */
    protected static final int MAX_BREVITY_LENGTH = 50;

    /**
     * The number of bytes to display when showing byte array
     */
    protected static final int MAX_BYTE_COUNT_BEFORE_TRUNCATION = 8;

    /**
     * The internal server error threshold defined by the HTTP protocol
     */
    public static final int INTERNAL_SERVER_ERROR = 500;

    /**
     * The GraphError response
     */
    private final transient GraphErrorResponse error;

    /**
     * The HTTP method
     */
    private final String method;

    /**
     * The request URL
     */
    private final String url;

    /**
     * The request headers
     */
    private final List<String> requestHeaders;

    /**
     * The request body represented as a string
     */
    private final String requestBody;

    /**
     * The HTTP status code
     */
    private final int responseCode;

    /**
     * The HTTP status message
     */
    private final String responseMessage;

    /**
     * The response headers
     */
    private final List<String> responseHeaders;

    /**
     * Whether to log the full error response
     */
    private final boolean verbose;

    /**
     * Create a Graph service exception
     *
     * @param method          the method that caused the exception
     * @param url             the URL
     * @param requestHeaders  the request headers
     * @param requestBody     the request body
     * @param responseCode    the response code
     * @param responseMessage the response message
     * @param responseHeaders the response headers
     * @param error           the error response if available
     * @param verbose         the error response log level
     */
    protected GraphServiceException(@Nonnull final String method,
                                    @Nonnull final String url,
                                    @Nonnull final List<String> requestHeaders,
                                    @Nullable final String requestBody,
                                    final int responseCode,
                                    @Nonnull final String responseMessage,
                                    @Nonnull final List<String> responseHeaders,
                                    @Nullable final GraphErrorResponse error,
                                    final boolean verbose) {
        super(responseMessage, null);
        this.method = Objects.requireNonNull(method, "parameter method cannot be null");
        this.url = Objects.requireNonNull(url, "parameter url cannot be null");
        this.requestHeaders = Objects.requireNonNull(requestHeaders, "parameter requestHeaders cannot be null");
        this.requestBody = requestBody;
        this.responseCode = responseCode;
        this.responseMessage = Objects.requireNonNull(responseMessage, "parameter responseMessage cannot be null");
        this.responseHeaders = Objects.requireNonNull(responseHeaders, "parameter responseHeaders cannot be null");
        this.error = error;
        this.verbose = verbose;
        for(String requestHeader : requestHeaders) {
            for(String headerKeyToRedact : requestHeadersToRedact) {
                if(requestHeader.startsWith(headerKeyToRedact)) {
                    Collections.replaceAll(requestHeaders, requestHeader, headerKeyToRedact + " : [PII_REDACTED]");
                    break;
                }
            }
        }
    }
    private static String[] requestHeadersToRedact = {"Authorization"};
    /**
     * Gets the The HTTP response message
     *
     * @return The HTTP response message
     */
    @Nullable
    public String getResponseMessage() {
    	return responseMessage;
    }

    @Override
    @Nullable
    public String getMessage() {
        return getMessage(verbose);
    }

    /**
     * Gets the HTTP status code
     *
     * @return The HTTP status response code
     */
    public int getResponseCode() {
    	return responseCode;
    }

    /**
     * Gets the response headers
     * @return the response headers
     */
    @Nonnull
    public List<String> getResponseHeaders() {
        return Collections.unmodifiableList(responseHeaders);
    }

    /**
     * Gets the error returned by the service
     * @return the error returned by the service
     */
    @Nullable
    public GraphErrorResponse getError() {
        return this.error.copy();
    }

    /**
     * Gets the HTTP method of the request
     * @return the HTTP method of the request
     */
    @Nonnull
    public String getMethod() {
        return method;
    }

    /**
     * Gets the URL of the request
     * @return the URL of the request
     */
    @Nonnull
    public String getUrl() {
        return url;
    }

    /**
     * Gets the request headers
     * @return the request headers
     */
    @Nonnull
    public List<String> getRequestHeaders() {
        return Collections.unmodifiableList(requestHeaders);
    }

    /**
     * Gets the message for this exception
     *
     * @param verbose if the message should be brief or more verbose
     * @return        the message.
     */
    @Nullable
    public String getMessage(final boolean verbose) {
        final StringBuilder sb = new StringBuilder();
        if (error != null && error.error != null) {
            sb.append("Error code: ").append(error.error.code).append(NEW_LINE);
            sb.append("Error message: ").append(error.error.message).append(NEW_LINE);
            sb.append(NEW_LINE);
        }
        // Request information
        sb.append(method).append(' ').append(url).append(NEW_LINE);
        for (final String header : requestHeaders) {
            if (verbose) {
                sb.append(header);
            } else {
                final String truncatedHeader = header.substring(0, Math.min(MAX_BREVITY_LENGTH, header.length()));
                sb.append(truncatedHeader);
                if (truncatedHeader.length() == MAX_BREVITY_LENGTH) {
                    sb.append(TRUNCATION_MARKER);
                }
            }
            sb.append(NEW_LINE);
        }
        if (requestBody != null) {
            if (verbose) {
                sb.append(requestBody);
            } else {
                sb.append(TRUNCATION_MARKER);
            }
        }
        sb.append(NEW_LINE).append(NEW_LINE);

        // Response information
        sb.append(responseCode).append(" : ").append(responseMessage).append(NEW_LINE);
        for (final String header : responseHeaders) {
            if (verbose) {
                sb.append(header).append(NEW_LINE);
            } else {
                if (header.toLowerCase(Locale.ROOT).startsWith("x-throwsite")) {
                    sb.append(header).append(NEW_LINE);
                }
            }
        }
        if (verbose && error != null && error.rawObject != null) {
            try {
                final Gson gson = new GsonBuilder().setPrettyPrinting().create();
                sb.append(gson.toJson(error.rawObject)).append(NEW_LINE);
            } catch (final RuntimeException ignored) {
                sb.append("[Warning: Unable to parse error message body]").append(NEW_LINE);
            }
        } else {
        	if (!verbose) {
        		sb.append(TRUNCATION_MARKER).append(NEW_LINE).append(NEW_LINE);
                sb.append("[Some information was truncated for brevity, enable debug logging for more details]");
        	}
        }
        return sb.toString();
    }

    /**
     * Gets the error message from the Graph service object
     *
     * @return the error message
     */
    @Nullable
    public GraphError getServiceError() {
        return error.error;
    }

    /**
     * Creates a Graph service exception from a given failed HTTP request
     *
     * @param request      the request that resulted in this failure
     * @param serializable the serialized object that was sent with this request
     * @param serializer   the serializer to re-create the option in its over the wire state
     * @param response   the response being used to extract information from
     * @param logger       the logger to log exception information to
     * @param <T>          the type of the serializable object
     * @return             the new GraphServiceException instance
     * @throws IOException an exception occurs if there were any problems processing the connection
     */
    @Nonnull
    public static <T> GraphServiceException createFromResponse(@Nonnull final IHttpRequest request,
                                                                 @Nullable final T serializable,
                                                                 @Nonnull final ISerializer serializer,
                                                                 @Nonnull final Response response,
                                                                 @Nonnull final ILogger logger)
            throws IOException {
        Objects.requireNonNull(response, "response parameter cannot be null");
        Objects.requireNonNull(request, "request parameter cannot be null");
        Objects.requireNonNull(serializer, "serializer parameter cannot be null");
        Objects.requireNonNull(logger, "logger parameter cannot be null");
        final String method = response.request().method();
        final String url = request.getRequestUrl().toString();
        final List<String> requestHeaders = new LinkedList<>();
        for (final HeaderOption option : request.getHeaders()) {
            requestHeaders.add(option.getName() + " : " + option.getValue());
        }
        final boolean isVerbose = logger.getLoggingLevel() == LoggerLevel.DEBUG;
        final String requestBody;
        if (serializable instanceof byte[]) {
            final byte[] bytes = (byte[]) serializable;
            StringBuilder sb = new StringBuilder();
            sb.append("byte[").append(bytes.length).append("]");

            sb.append(" {");
            if (isVerbose) {
            	sb.append(Arrays.toString(bytes));
            } else {
	            for (int i = 0; i < MAX_BYTE_COUNT_BEFORE_TRUNCATION && i < bytes.length; i++) {
	                sb.append(bytes[i]).append(", ");
	            }
	            if (bytes.length > MAX_BYTE_COUNT_BEFORE_TRUNCATION) {
	                sb.append(TRUNCATION_MARKER).append("}");
	            }
            }
            requestBody = sb.toString();
        } else if (serializable != null) {
            requestBody = serializer.serializeObject(serializable);
        } else {
            requestBody = null;
        }

        final int responseCode = response.code();
        final Map<String, String> headers = getResponseHeadersAsMapStringString(response);
        final String responseMessage = response.message();
        GraphErrorResponse error = parseErrorResponse(serializer, response);

        return createFromResponse(url, method, requestHeaders, requestBody, headers, responseMessage, responseCode, error, isVerbose);

    }
    /**
     * Creates a Graph service exception.
     * @param url url of the original request
     * @param method http method of the original request
     * @param requestHeaders headers of the original request
     * @param requestBody body of the original request
     * @param headers response headers
     * @param responseMessage reponse status message
     * @param responseCode response status code
     * @param error graph error response object
     * @param isVerbose whether to display a verbose message or not
     * @return the Exception to be thrown
     */
    @Nonnull
    public static GraphServiceException createFromResponse(@Nullable final String url, @Nullable final String method, @Nonnull final List<String> requestHeaders,
        @Nullable final String requestBody,
        @Nonnull final Map<String,String> headers, @Nonnull final String responseMessage, final int responseCode,
        @Nonnull final GraphErrorResponse error, final boolean isVerbose) {
        Objects.requireNonNull(headers, "parameter headers cannot be null");
        final List<String> responseHeaders = new LinkedList<>();
        for (final Entry<String, String> entry : headers.entrySet()) {
            responseHeaders.add(entry.getKey() + (entry.getKey() == null ? "" : " : " ) + entry.getValue());
        }


        if (responseCode >= INTERNAL_SERVER_ERROR) {
            return new GraphFatalServiceException(method == null ? "" : method,
                    url == null ? "" : url,
                    requestHeaders,
                    requestBody,
                    responseCode,
                    responseMessage,
                    responseHeaders,
                    error,
                    isVerbose);
        }

        return new GraphServiceException(method == null ? "" : method,
                url == null ? "" : url,
                requestHeaders,
                requestBody,
                responseCode,
                responseMessage,
                responseHeaders,
                error,
                isVerbose);
    }

    private static GraphErrorResponse parseErrorResponse(@Nonnull ISerializer serializer,
                                                         @Nonnull Response response)
            throws IOException {
        Objects.requireNonNull(serializer, "serializer is required.");
        Objects.requireNonNull(response, "response is required.");
        byte[] responseBytes;
        try(final ResponseBody body = response.body()) {
            if(body == null) {
                responseBytes = new byte[]{};
            } else {
                try(final InputStream is = body.byteStream()) {
                    responseBytes = ByteStreams.toByteArray(is);
                }
            }
        }
        GraphErrorResponse error;
        try {
            // we need a "copy" of the stream, so we can log the raw output if it cannot be parsed
            error = serializer.deserializeObject(
                    new ByteArrayInputStream(responseBytes),
                    GraphErrorResponse.class,
                    response.headers().toMultimap()
            );
        } catch (final Exception ex) {
            error = new GraphErrorResponse();
            error.error = new GraphError();
            error.error.code = "Unable to parse error response message";
            error.error.message = "Raw error: " + new String(responseBytes, UTF_8);
            error.error.innererror = new GraphInnerError();
            error.error.innererror.code = ex.getMessage();
        }
        return error;
    }

    /**
     * Gets the response headers from OkHttp Response
     *
     * @param response the OkHttp response
     * @return           the set of headers names and value
     */
    @Nonnull
    protected static Map<String, String> getResponseHeadersAsMapStringString(@Nonnull final Response response) {
        final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        int index = 0;
        final Headers responseHeaders = response.headers();
        while (index < responseHeaders.size()) {
            final String headerName = responseHeaders.name(index);
            final String headerValue = responseHeaders.value(index);
            if (headerName == null || headerValue == null) {
                break;
            }
            headers.put(headerName, headerValue);
            index++;
        }
        return headers;
    }
}

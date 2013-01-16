package org.jolokia.converter.json;


import java.lang.reflect.InvocationTargetException;
import java.util.*;

import javax.management.AttributeNotFoundException;

import org.jolokia.converter.ValueFaultHandler;
import org.jolokia.converter.object.StringToObjectConverter;
import org.jolokia.util.EscapeUtil;
import org.jolokia.util.ServiceObjectFactory;
import org.json.simple.JSONAware;
import org.json.simple.JSONObject;

/*
 *  Copyright 2009-2010 Roland Huss
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */


/**
 * A converter which converts attribute and return values
 * into a JSON representation. It uses certain handlers for this which
 * are registered in the constructor.
 *
 * Each handler gets a reference to this converter object so that it
 * can use it for a recursive solution of nested objects.
 *
 * @author roland
 * @since Apr 19, 2009
 */
public final class ObjectToJsonConverter {

    // List of dedicated handlers used for delegation in serialization/deserializatin
    private List<Extractor> handlers;

    private ArrayExtractor arrayExtractor;

    // Thread-Local set in order to prevent infinite recursions
    private ThreadLocal<ObjectSerializationContext> stackContextLocal = new ThreadLocal<ObjectSerializationContext>();

    // Used for converting string to objects when setting attributes
    private StringToObjectConverter stringToObjectConverter;

    // Definition of simplifiers
    private static final String SIMPLIFIERS_DEFAULT_DEF = "META-INF/simplifiers-default";
    private static final String SIMPLIFIERS_DEF         = "META-INF/simplifiers";

    /**
     * New object-to-json converter
     *
     * @param pStringToObjectConverter used when setting values
     * @param pSimplifyHandlers a bunch of simplifiers used for mangling the conversion result
     */
    public ObjectToJsonConverter(StringToObjectConverter pStringToObjectConverter,
                                 Extractor... pSimplifyHandlers) {

        handlers = new ArrayList<Extractor>();

        // TabularDataExtractor must be before MapExtractor
        // since TabularDataSupport isa Map
        handlers.add(new TabularDataExtractor());
        handlers.add(new CompositeDataExtractor());

        // Collection handlers
        handlers.add(new ListExtractor());
        handlers.add(new MapExtractor());
        handlers.add(new CollectionExtractor());

        // Special, well known objects
        addSimplifiers(handlers, pSimplifyHandlers);

        // Special date handling
        handlers.add(new DateExtractor());

        // Must be last in handlers, used default algorithm
        handlers.add(new BeanExtractor());

        arrayExtractor = new ArrayExtractor();

        stringToObjectConverter = pStringToObjectConverter;
    }


    /**
     * Convert the return value to a JSON object.
     *
     *
     * @param pValue the value to convert
     * @param pOptions options used for parsing
     * @param pPathParts path parts to use for extraction
     * @return the converter object. This either a subclass of {@link JSONAware} or a basic data type like String or Long.
     * @throws AttributeNotFoundException if within an path an attribute could not be found
     */
    public Object convertToJson(Object pValue, JsonConvertOptions pOptions, List<String> pPathParts)
            throws AttributeNotFoundException {
        Stack<String> extraStack = pPathParts != null ? EscapeUtil.reversePath(pPathParts) : new Stack<String>();
        return extractObjectWithContext(pOptions, pValue, extraStack, true);
    }


    /**
     * Handle a value which means to dive into the internal of a complex object
     * (if <code>pExtraArgs</code> is not null) and/or to convert
     * it to JSON (if <code>pJsonify</code> is true).
     *
     * @param pOpts options from which various processing
     *        parameters (like maxDepth, maxCollectionSize and maxObjects) are taken and put
     *        into context in order to influence the object traversal.
     * @param pValue value to extract from
     * @param pExtraArgs stack used for diving in to the value
     * @param pJsonify whether the result should be returned as an JSON object
     * @return extracted value, either natively or as JSON
     * @throws AttributeNotFoundException if during traversal an attribute is not found as specified in the stack
     */
    private Object extractObjectWithContext(JsonConvertOptions pOpts, Object pValue, Stack<String> pExtraArgs, boolean pJsonify)
            throws AttributeNotFoundException {
        Object jsonResult;
        setupContext(pOpts);
        try {
            jsonResult = extractObject(pValue, pExtraArgs, pJsonify);
        } finally {
            clearContext();
        }
        return jsonResult;
    }

    /**
     * Related to {@link #extractObjectWithContext(JsonConvertOptions, Object, Stack, boolean)} except that
     * it does not setup a context. This method is used from the
     * various extractors for recursively continuing the extraction
     *
     * @param pValue value to extract from
     * @param pExtraArgs stack for diving into the object
     * @param pJsonify whether a JSON representation {@link JSONObject}
     * @return extracted object either in native format or as {@link JSONObject}
     * @throws AttributeNotFoundException if an attribute is not found during traversal
     */
    public Object extractObject(Object pValue, Stack<String> pExtraArgs, boolean pJsonify)
            throws AttributeNotFoundException {
        ObjectSerializationContext stackContext = stackContextLocal.get();
        String limitReached = checkForLimits(pValue,stackContext);
        Stack<String> pathStack = pExtraArgs != null ? pExtraArgs : new Stack<String>();
        if (limitReached != null) {
            return limitReached;
        }
        try {
            stackContext.push(pValue);

            if (pValue == null) {
                return null;
            }

            if (pValue.getClass().isArray()) {
                // Special handling for arrays
                return arrayExtractor.extractObject(this,pValue,pathStack,pJsonify);
            }
            return callHandler(pValue, pathStack, pJsonify);
        } finally {
            stackContext.pop();
        }
    }

    /**
     * Set an value of an inner object
     *
     * @param pInner the inner object
     * @param pAttribute the attribute to set
     * @param pValue the value to set
     * @return the old value
     * @throws IllegalAccessException if the reflection code fails during setting of the value
     * @throws InvocationTargetException reflection error
     */
    public Object setObjectValue(Object pInner, String pAttribute, Object pValue)
            throws IllegalAccessException, InvocationTargetException {

        // Call various handlers depending on the type of the inner object, as is extract Object

        Class clazz = pInner.getClass();
        if (clazz.isArray()) {
            return arrayExtractor.setObjectValue(stringToObjectConverter,pInner,pAttribute,pValue);
        }
        Extractor handler = getExtractor(clazz);

        if (handler != null) {
            return handler.setObjectValue(stringToObjectConverter,pInner,pAttribute,pValue);
        } else {
            throw new IllegalStateException(
                    "Internal error: No handler found for class " + clazz + " for setting object value." +
                    " (object: " + pInner + ", attribute: " + pAttribute + ", value: " + pValue + ")");
        }
    }

    // =================================================================================

    /**
     * Get the length of an extracted collection, but not larger than the configured limit.
     *
     * @param originalLength the orginal length
     * @return the original length if is smaller than then the configured maximum length. Otherwise the
     *         maximum length is returned.
     */
    int getCollectionLength(int originalLength) {
        ObjectSerializationContext ctx = stackContextLocal.get();
        return ctx.getCollectionSizeTruncated(originalLength);
    }

    /**
     * Get the fault handler used for dealing with exceptions during value extraction.
     *
     * @return the fault handler
     */
    public ValueFaultHandler getValueFaultHandler() {
        ObjectSerializationContext ctx = stackContextLocal.get();
        return ctx.getValueFaultHandler();
    }

    /**
     * Clear the context used for counting objects and limits
     */
    void clearContext() {
        stackContextLocal.remove();
    }

    /**
     * Setup the context with hard limits
     */
    void setupContext() {
        setupContext(new JsonConvertOptions.Builder().build());
    }

    /**
     * Setup the context with the limits given in the request or with the default limits if not. In all cases,
     * hard limits as defined in the servlet configuration are never exceeded.
     *
     * @param pOpts options used for parsing.
     */
    void setupContext(JsonConvertOptions pOpts) {
        ObjectSerializationContext stackContext = new ObjectSerializationContext(pOpts);
        stackContextLocal.set(stackContext);
    }

    // =================================================================================

    // Get the extractor for a certain class
    private Extractor getExtractor(Class pClazz) {
        for (Extractor handler : handlers) {
            if (handler.canSetValue() && handler.getType() != null && handler.getType().isAssignableFrom(pClazz)) {
                return handler;
            }
        }
        return null;
    }

    private String checkForLimits(Object pValue, ObjectSerializationContext pStackContext) {
        if (pValue != null) {
            if (pStackContext.maxDepthReached()) {
                // We use its string representation.
                return pValue.toString();
            }
            if (pStackContext.alreadyVisited(pValue)) {
                return "[Reference " + pValue.getClass().getName() + "@" + Integer.toHexString(pValue.hashCode()) + "]";
            }
        }
        if (pStackContext.maxObjectsExceeded()) {
            return "[Object limit exceeded]";
        }
        return null;
    }

    private Object callHandler(Object pValue, Stack<String> pExtraArgs, boolean pJsonify)
            throws AttributeNotFoundException {
        Class pClazz = pValue.getClass();
        for (Extractor handler : handlers) {
            if (handler.getType() != null && handler.getType().isAssignableFrom(pClazz)) {
                return handler.extractObject(this,pValue,pExtraArgs,pJsonify);
            }
        }
        throw new IllegalStateException(
                "Internal error: No handler found for class " + pClazz +
                    " (object: " + pValue + ", extraArgs: " + pExtraArgs + ")");
    }



    // Used for testing only. Hence final and package local
    ThreadLocal<ObjectSerializationContext> getStackContextLocal() {
        return stackContextLocal;
    }


    private Integer getLimit(Integer pReqValue, Integer pHardLimit) {
        if (pReqValue == null) {
            return pHardLimit;
        }
        if (pHardLimit != null) {
            return pReqValue > pHardLimit ? pHardLimit : pReqValue;
        } else {
            return pReqValue;
        }
    }


    // Simplifiers are added either explicitely or by reflection from a subpackage
    private void addSimplifiers(List<Extractor> pHandlers, Extractor[] pSimplifyHandlers) {
        if (pSimplifyHandlers != null && pSimplifyHandlers.length > 0) {
            pHandlers.addAll(Arrays.asList(pSimplifyHandlers));
        } else {
            // Add all
            pHandlers.addAll(ServiceObjectFactory.<Extractor>createServiceObjects(SIMPLIFIERS_DEFAULT_DEF, SIMPLIFIERS_DEF));
        }
    }

}

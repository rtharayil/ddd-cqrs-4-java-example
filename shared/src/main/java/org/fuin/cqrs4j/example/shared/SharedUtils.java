/**
 * Copyright (C) 2015 Michael Schnell. All rights reserved. 
 * http://www.fuin.org/
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option) any
 * later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library. If not, see http://www.gnu.org/licenses/.
 */
package org.fuin.cqrs4j.example.shared;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.zip.Adler32;

import javax.json.bind.adapter.JsonbAdapter;
import javax.json.bind.config.PropertyVisibilityStrategy;

import org.fuin.ddd4j.ddd.AggregateVersionConverter;
import org.fuin.ddd4j.ddd.EntityIdConverter;
import org.fuin.ddd4j.ddd.EntityIdPathConverter;
import org.fuin.ddd4j.ddd.EventIdConverter;
import org.fuin.ddd4j.ddd.EventType;
import org.fuin.esc.spi.Base64Data;
import org.fuin.esc.spi.EscEvent;
import org.fuin.esc.spi.EscEvents;
import org.fuin.esc.spi.EscMeta;
import org.fuin.esc.spi.EscSpiUtils;
import org.fuin.esc.spi.JsonbDeSerializer;
import org.fuin.esc.spi.SerDeserializerRegistry;
import org.fuin.esc.spi.SerializedDataType;
import org.fuin.esc.spi.SerializedDataTypeRegistry;
import org.fuin.esc.spi.SimpleSerializedDataTypeRegistry;
import org.fuin.esc.spi.SimpleSerializerDeserializerRegistry;

/**
 * Utility code shared between command (write) and query (read) module.
 */
public final class SharedUtils {

    private static final String APPLICATION_JSON = "application/json";

    /** All types that will be written into and read from the event store. */
    private static final TypeClass[] USER_DEFINED_TYPES = new TypeClass[] {
            new TypeClass(PersonCreatedEvent.SER_TYPE, PersonCreatedEvent.class) };

    /** All JSON-B adapters from this module. */
    public static final JsonbAdapter<?, ?>[] JSONB_ADAPTERS = new JsonbAdapter<?, ?>[] { new EventIdConverter(),
            new EntityIdPathConverter(new SharedEntityIdFactory()), new EntityIdConverter(new SharedEntityIdFactory()),
            new AggregateVersionConverter(), new PersonId.Converter(), new PersonName.Converter() };

    private SharedUtils() {
        throw new UnsupportedOperationException("It is not allowed to create an instance of a utiliy class");
    }

    /**
     * Create a registry that allows finding types (classes) based on their unique type name.
     * 
     * @return New instance.
     */
    public static SerializedDataTypeRegistry createTypeRegistry() {

        // Contains all types for usage with JSON-B
        final SimpleSerializedDataTypeRegistry typeRegistry = new SimpleSerializedDataTypeRegistry();

        // Base types always needed
        typeRegistry.add(EscEvent.SER_TYPE, EscEvent.class);
        typeRegistry.add(EscEvents.SER_TYPE, EscEvents.class);
        typeRegistry.add(EscMeta.SER_TYPE, EscMeta.class);
        typeRegistry.add(Base64Data.SER_TYPE, Base64Data.class);

        // User defined types
        for (final TypeClass tc : USER_DEFINED_TYPES) {
            typeRegistry.add(tc.getType(), tc.getClasz());
        }
        return typeRegistry;

    }

    /**
     * Creates a registry that connects the type with the appropriate serializer and de-serializer.
     * 
     * @param typeRegistry
     *            Type registry (Mapping from type name to class).
     * @param jsonbDeSer
     *            JSON-B serializer/deserializer to use.
     * 
     * @return New instance.
     */
    public static SerDeserializerRegistry createSerDeserializerRegistry(final SerializedDataTypeRegistry typeRegistry,
            final JsonbDeSerializer jsonbDeSer) {

        final SimpleSerializerDeserializerRegistry registry = new SimpleSerializerDeserializerRegistry();

        // Base types always needed
        registry.add(EscEvents.SER_TYPE, APPLICATION_JSON, jsonbDeSer);
        registry.add(EscEvent.SER_TYPE, APPLICATION_JSON, jsonbDeSer);
        registry.add(EscMeta.SER_TYPE, APPLICATION_JSON, jsonbDeSer);
        registry.add(Base64Data.SER_TYPE, APPLICATION_JSON, jsonbDeSer);

        // User defined types
        for (final TypeClass tc : USER_DEFINED_TYPES) {
            registry.add(tc.getType(), APPLICATION_JSON, jsonbDeSer);
        }
        jsonbDeSer.init(typeRegistry, registry, registry);

        return registry;
    }

    /**
     * Creates a registry that connects the type with the appropriate serializer and de-serializer.
     * 
     * @return New instance.
     */
    public static SerDeserializerRegistry createRegistry() {

        // Knows about all types for usage with JSON-B
        final SerializedDataTypeRegistry typeRegistry = SharedUtils.createTypeRegistry();

        // Does the actual marshalling/unmarshalling
        final JsonbDeSerializer jsonbDeSer = SharedUtils.createJsonbDeSerializer();

        // Registry connects the type with the appropriate serializer and de-serializer
        return SharedUtils.createSerDeserializerRegistry(typeRegistry, jsonbDeSer);

    }

    /**
     * Creates an instance of the JSON-B serializer/deserializer.
     * 
     * @return New instance that is fully initialized with al necessary settings.
     */
    public static JsonbDeSerializer createJsonbDeSerializer() {

        return JsonbDeSerializer.builder().withSerializers(EscSpiUtils.createEscJsonbSerializers())
                .withDeserializers(EscSpiUtils.createEscJsonbDeserializers()).withAdapters(JSONB_ADAPTERS)
                .withPropertyVisibilityStrategy(new FieldAccessStrategy()).withEncoding(StandardCharsets.UTF_8).build();

    }

    /**
     * Creates an Adler32 checksum based on on event type names.
     * 
     * @param eventTypes
     *            Types to calculate a checksum for.
     * 
     * @return Checksum based on all names.
     */
    public static long calculateChecksum(final Collection<EventType> eventTypes) {
        final Adler32 checksum = new Adler32();
        for (final EventType eventType : eventTypes) {
            checksum.update(eventType.asBaseType().getBytes(StandardCharsets.US_ASCII));
        }
        return checksum.getValue();
    }

    private static class FieldAccessStrategy implements PropertyVisibilityStrategy {

        @Override
        public boolean isVisible(Field field) {
            return true;
        }

        @Override
        public boolean isVisible(Method method) {
            return false;
        }

    }

    /**
     * Helper class for type/class combination.
     */
    private static final class TypeClass {

        private final SerializedDataType type;

        private final Class<?> clasz;

        /**
         * Constructor with all data.
         * 
         * @param type
         *            Type.
         * @param clasz
         *            Class.
         */
        public TypeClass(final SerializedDataType type, final Class<?> clasz) {
            super();
            this.type = type;
            this.clasz = clasz;
        }

        /**
         * Returns the type.
         * 
         * @return Type.
         */
        public SerializedDataType getType() {
            return type;
        }

        /**
         * Returns the class.
         * 
         * @return Class.
         */
        public Class<?> getClasz() {
            return clasz;
        }

    }

}
/* Copyright (c) 2008-2017, Nathan Sweet
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following
 * conditions are met:
 * 
 * - Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the distribution.
 * - Neither the name of Esoteric Software nor the names of its contributors may be used to endorse or promote products derived
 * from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT
 * SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. */

package com.esotericsoftware.kryo.serializers;

import static com.esotericsoftware.kryo.util.Util.*;
import static com.esotericsoftware.minlog.Log.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.TypeVariable;
import java.util.Arrays;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.serializers.GenericsResolver.GenericsScope;
import com.esotericsoftware.reflectasm.FieldAccess;

/** Serializes objects using direct field assignment. FieldSerializer is generic and can serialize most classes without any
 * configuration. It is efficient and writes only the field data, without any extra information. It does not support adding,
 * removing, or changing the type of fields without invalidating previously serialized bytes. This can be acceptable in many
 * situations, such as when sending data over a network, but may not be a good choice for long term data storage because the Java
 * classes cannot evolve. Because FieldSerializer attempts to read and write non-public fields by default, it is important to
 * evaluate each class that will be serialized. If fields are public, bytecode generation will be used instead of reflection.
 * @see Serializer
 * @see Kryo#register(Class, Serializer)
 * @see VersionFieldSerializer
 * @see TaggedFieldSerializer
 * @see CompatibleFieldSerializer
 * @author Nathan Sweet
 * @author Roman Levenstein <romixlev@gmail.com> */
public class FieldSerializer<T> extends Serializer<T> {
	final Kryo kryo;
	final Class type;
	final FieldSerializerConfig config;
	final CachedFields cachedFields;

	final FieldSerializerGenerics generics;
	Class[] genericTypes;
	GenericsScope genericsScope;

	public FieldSerializer (Kryo kryo, Class type) {
		this(kryo, type, new FieldSerializerConfig());
	}

	public FieldSerializer (Kryo kryo, Class type, FieldSerializerConfig config) {
		if (config == null) throw new IllegalArgumentException("config cannot be null.");
		this.kryo = kryo;
		this.type = type;
		this.config = config;

		generics = new FieldSerializerGenerics(this, type);

		cachedFields = new CachedFields(this);
		cachedFields.rebuild();
	}

	/** Must be called after config settings are changed. */
	public void updateConfig () {
		if (TRACE) trace("kryo", "Update FieldSerializerConfig: " + className(type));
		genericsScope = null;
		cachedFields.rebuild();
	}

	protected void initializeCachedFields () {
	}

	/** This method can be called for different fields having the same type. Even though the raw type is the same, if the type is
	 * generic, it could happen that different concrete classes are used to instantiate it. Therefore, in case of different
	 * instantiation parameters, the fields analysis should be repeated. */
	public void write (Kryo kryo, Output output, T object) {
		if (config.optimizedGenerics) {
			if (generics.typeParameters.length > 0 && genericTypes != null) updateGenerics();
			if (genericsScope != null) kryo.getGenericsResolver().pushScope(type, genericsScope);
		}

		CachedField[] fields = cachedFields.fields;
		for (int i = 0, n = fields.length; i < n; i++) {
			if (TRACE) log("Write", fields[i], output.position());
			fields[i].write(output, object);
		}

		if (config.optimizedGenerics && genericsScope != null) kryo.getGenericsResolver().popScope();
	}

	public T read (Kryo kryo, Input input, Class<? extends T> type) {
		if (config.optimizedGenerics) {
			if (generics.typeParameters.length > 0 && genericTypes != null) updateGenerics();
			if (genericsScope != null) kryo.getGenericsResolver().pushScope(type, genericsScope);
		}

		T object = create(kryo, input, type);
		kryo.reference(object);

		CachedField[] fields = cachedFields.fields;
		for (int i = 0, n = fields.length; i < n; i++) {
			if (TRACE) log("Read", fields[i], input.position());
			fields[i].read(input, object);
		}

		if (config.optimizedGenerics && genericsScope != null) kryo.getGenericsResolver().popScope();

		return object;
	}

	/** Used by {@link #read(Kryo, Input, Class)} to create the new object. This can be overridden to customize object creation, eg
	 * to call a constructor with arguments. The default implementation uses {@link Kryo#newInstance(Class)}. */
	protected T create (Kryo kryo, Input input, Class<? extends T> type) {
		return kryo.newInstance(type);
	}

	protected void log (String prefix, CachedField cachedField, int position) {
		trace("kryo", prefix + " " + cachedField.field.getType().getSimpleName() + " field: " + cachedField + " ("
			+ className(cachedField.field.getDeclaringClass()) + ")" + " pos=" + position);
	}

	public void setGenerics (Kryo kryo, Class[] genericTypes) {
		this.genericTypes = genericTypes;
	}

	private void updateGenerics () {
		if (TRACE && genericTypes != null) trace("kryo", "Generic type parameters: " + Arrays.toString(genericTypes));

		// Generate a mapping from type variable names to concrete types.
		genericsScope = generics.newGenericsScope(type, genericTypes);
		if (genericsScope != null) kryo.getGenericsResolver().pushScope(type, genericsScope);

		for (CachedField cachedField : cachedFields.fields)
			if (cachedField instanceof ReflectField) generics.updateGenericCachedField((ReflectField)cachedField);

		if (genericsScope != null) kryo.getGenericsResolver().popScope();
	}

	/** Allows specific fields to be optimized. */
	public CachedField getField (String fieldName) {
		for (CachedField cachedField : cachedFields.fields)
			if (cachedField.name.equals(fieldName)) return cachedField;
		throw new IllegalArgumentException("Field \"" + fieldName + "\" not found on class: " + type.getName());
	}

	public void removeField (String fieldName) {
		cachedFields.removeField(fieldName);
	}

	public void removeField (CachedField field) {
		cachedFields.removeField(field);
	}

	/** Returns the fields used for serialization. */
	public CachedField[] getFields () {
		return cachedFields.fields;
	}

	/** Returns the fields used for copying. */
	public CachedField[] getCopyFields () {
		return cachedFields.copyFields;
	}

	public Class getType () {
		return type;
	}

	public Kryo getKryo () {
		return kryo;
	}

	/** Used by {@link #copy(Kryo, Object)} to create a new object. This can be overridden to customize object creation, eg to call
	 * a constructor with arguments. The default implementation uses {@link Kryo#newInstance(Class)}. */
	protected T createCopy (Kryo kryo, T original) {
		return (T)kryo.newInstance(original.getClass());
	}

	public T copy (Kryo kryo, T original) {
		T copy = createCopy(kryo, original);
		kryo.reference(copy);

		for (int i = 0, n = cachedFields.copyFields.length; i < n; i++)
			cachedFields.copyFields[i].copy(original, copy);

		return copy;
	}

	public FieldSerializerConfig getFieldSerializerConfig () {
		return config;
	}

	/** Settings for serializing a field. */
	static public abstract class CachedField {
		Field field;
		FieldAccess access;
		String name;
		Class valueClass;
		Serializer serializer;
		boolean canBeNull, varInt = true, optimizePositive;
		int accessIndex = -1;

		/** @param valueClass The concrete class of the values for this field. This saves 1-2 bytes. The serializer registered for
		 *           the specified class will be used. Only set to a non-null value if the field type in the class definition is
		 *           final or the values for this field will not vary. */
		public void setClass (Class valueClass) {
			this.valueClass = valueClass;
			this.serializer = null;
		}

		/** @param valueClass The concrete class of the values for this field. This saves 1-2 bytes. Only set to a non-null value if
		 *           the field type in the class definition is final or the values for this field will not vary. */
		public void setClass (Class valueClass, Serializer serializer) {
			this.valueClass = valueClass;
			this.serializer = serializer;
		}

		public void setSerializer (Serializer serializer) {
			this.serializer = serializer;
		}

		public Serializer getSerializer () {
			return this.serializer;
		}

		public void setCanBeNull (boolean canBeNull) {
			this.canBeNull = canBeNull;
		}

		public boolean getCanBeNull () {
			return canBeNull;
		}

		/** When true, variable length values are used for int and long fields. Default is true. */
		public void setVarInt (boolean varInt) {
			this.varInt = varInt;
		}

		public boolean getVarInt () {
			return varInt;
		}

		/** When true, variable length int and long values are written with fewer bytes when the values are positive. Default is
		 * false. */
		public void setOptimizePositive (boolean optimizePositive) {
			this.optimizePositive = optimizePositive;
		}

		public boolean getOptimizePositive () {
			return optimizePositive;
		}

		public String getName () {
			return name;
		}

		public Field getField () {
			return field;
		}

		public String toString () {
			return name;
		}

		abstract public void write (Output output, Object object);

		abstract public void read (Input input, Object object);

		abstract public void copy (Object original, Object copy);
	}

	/** Indicates a field should be ignored when its declaring class is registered unless the {@link Kryo#getContext() context} has
	 * a value set for the specified key. This can be useful when a field must be serialized for one purpose, but not for another.
	 * Eg, a class for a networked application could have a field that should not be serialized and sent to clients, but should be
	 * serialized when stored on the server.
	 * @author Nathan Sweet */
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.FIELD)
	public @interface Optional {
		public String value();
	}

	/** Used to annotate fields with a specific Kryo serializer. */
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.FIELD)
	public @interface Bind {
		/** The serializer class to use for this field. */
		Class<? extends Serializer> value();
	}
}

/*
 * Copyright © 2009 Reinier Zwitserloot and Roel Spilker.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package lombok.eclipse;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.core.AnnotationValues;
import lombok.core.TypeLibrary;
import lombok.core.TypeResolver;
import lombok.core.AST.Kind;
import lombok.core.AnnotationValues.AnnotationValue;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.Annotation;
import org.eclipse.jdt.internal.compiler.ast.ArrayInitializer;
import org.eclipse.jdt.internal.compiler.ast.ArrayQualifiedTypeReference;
import org.eclipse.jdt.internal.compiler.ast.ArrayTypeReference;
import org.eclipse.jdt.internal.compiler.ast.ClassLiteralAccess;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.Expression;
import org.eclipse.jdt.internal.compiler.ast.Literal;
import org.eclipse.jdt.internal.compiler.ast.MarkerAnnotation;
import org.eclipse.jdt.internal.compiler.ast.MemberValuePair;
import org.eclipse.jdt.internal.compiler.ast.NormalAnnotation;
import org.eclipse.jdt.internal.compiler.ast.ParameterizedQualifiedTypeReference;
import org.eclipse.jdt.internal.compiler.ast.ParameterizedSingleTypeReference;
import org.eclipse.jdt.internal.compiler.ast.QualifiedNameReference;
import org.eclipse.jdt.internal.compiler.ast.QualifiedTypeReference;
import org.eclipse.jdt.internal.compiler.ast.SingleMemberAnnotation;
import org.eclipse.jdt.internal.compiler.ast.SingleNameReference;
import org.eclipse.jdt.internal.compiler.ast.SingleTypeReference;
import org.eclipse.jdt.internal.compiler.ast.TypeParameter;
import org.eclipse.jdt.internal.compiler.ast.TypeReference;
import org.eclipse.jdt.internal.compiler.ast.Wildcard;
import org.eclipse.jdt.internal.compiler.lookup.TypeIds;
import org.osgi.framework.Bundle;

public class Eclipse {
	/**
	 * Eclipse's Parser class is instrumented to not attempt to fill in the body of any method or initializer
	 * or field initialization if this flag is set. Set it on the flag field of
	 * any method, field, or initializer you create!
	 */
	public static final int ECLIPSE_DO_NOT_TOUCH_FLAG = ASTNode.Bit24;
	
	private Eclipse() {
		//Prevent instantiation
	}
	
	private static final String DEFAULT_BUNDLE = "org.eclipse.jdt.core";
	
	/**
	 * Generates an error in the Eclipse error log. Note that most people never look at it!
	 */
	public static void error(CompilationUnitDeclaration cud, String message) {
		error(cud, message, DEFAULT_BUNDLE, null);
	}
	
	/**
	 * Generates an error in the Eclipse error log. Note that most people never look at it!
	 */
	public static void error(CompilationUnitDeclaration cud, String message, Throwable error) {
		error(cud, message, DEFAULT_BUNDLE, error);
	}
	
	/**
	 * Generates an error in the Eclipse error log. Note that most people never look at it!
	 */
	public static void error(CompilationUnitDeclaration cud, String message, String bundleName) {
		error(cud, message, bundleName, null);
	}
	
	/**
	 * Generates an error in the Eclipse error log. Note that most people never look at it!
	 */
	public static void error(CompilationUnitDeclaration cud, String message, String bundleName, Throwable error) {
		try {
			new EclipseWorkspaceLogger().error(message, bundleName, error);
		} catch (NoClassDefFoundError e) {	//standalone ecj does not jave Platform, ILog, IStatus, and friends.
			new TerminalLogger().error(message, bundleName, error);
		}
		if (cud != null) EclipseAST.addProblemToCompilationResult(cud, false, message + " - See error log.", 0, 0);
	}
	
	private static class TerminalLogger {
		@SuppressWarnings("unused")	//to match signature of EclipseWorkspaceLogger.
		void error(String message, String bundleName, Throwable error) {
			System.err.println(message);
			error.printStackTrace();
		}
	}
	
	private static class EclipseWorkspaceLogger {
		void error(String message, String bundleName, Throwable error) {
			Bundle bundle = Platform.getBundle(bundleName);
			if (bundle == null) {
				System.err.printf("Can't find bundle %s while trying to report error:\n%s\n", bundleName, message);
				return;
			}
			
			ILog log = Platform.getLog(bundle);
			
			log.log(new Status(IStatus.ERROR, bundleName, message, error));
		}
	}
	
	/**
	 * For 'speed' reasons, Eclipse works a lot with char arrays. I have my doubts this was a fruitful exercise,
	 * but we need to deal with it. This turns [[java][lang][String]] into "java.lang.String".
	 */
	public static String toQualifiedName(char[][] typeName) {
		StringBuilder sb = new StringBuilder();
		boolean first = true;
		for (char[] c : typeName) {
			sb.append(first ? "" : ".").append(c);
			first = false;
		}
		return sb.toString();
	}
	
	public static char[][] fromQualifiedName(String typeName) {
		String[] split = typeName.split("\\.");
		char[][] result = new char[split.length][];
		for (int i = 0; i < split.length; i++) {
			result[i] = split[i].toCharArray();
		}
		return result;
	}
	
	
	/**
	 * You can't share TypeParameter objects or bad things happen; for example, one 'T' resolves differently
	 * from another 'T', even for the same T in a single class file. Unfortunately the TypeParameter type hierarchy
	 * is complicated and there's no clone method on TypeParameter itself. This method can clone them.
	 */
	public static TypeParameter[] copyTypeParams(TypeParameter[] params, ASTNode source) {
		if (params == null) return null;
		TypeParameter[] out = new TypeParameter[params.length];
		int idx = 0;
		for (TypeParameter param : params) {
			TypeParameter o = new TypeParameter();
			setGeneratedBy(o, source);
			o.annotations = param.annotations;
			o.bits = param.bits;
			o.modifiers = param.modifiers;
			o.name = param.name;
			o.type = copyType(param.type, source);
			o.sourceStart = param.sourceStart;
			o.sourceEnd = param.sourceEnd;
			o.declarationEnd = param.declarationEnd;
			o.declarationSourceStart = param.declarationSourceStart;
			o.declarationSourceEnd = param.declarationSourceEnd;
			if (param.bounds != null) {
				TypeReference[] b = new TypeReference[param.bounds.length];
				int idx2 = 0;
				for (TypeReference ref : param.bounds) b[idx2++] = copyType(ref, source);
				o.bounds = b;
			}
			out[idx++] = o;
		}
		return out;
	}
	
	/**
	 * Convenience method that creates a new array and copies each TypeReference in the source array via
	 * {@link #copyType(TypeReference, ASTNode)}.
	 */
	public static TypeReference[] copyTypes(TypeReference[] refs, ASTNode source) {
		if (refs == null) return null;
		TypeReference[] outs = new TypeReference[refs.length];
		int idx = 0;
		for (TypeReference ref : refs) {
			outs[idx++] = copyType(ref, source);
		}
		return outs;
	}
	
	/**
	 * You can't share TypeReference objects or subtle errors start happening.
	 * Unfortunately the TypeReference type hierarchy is complicated and there's no clone
	 * method on TypeReference itself. This method can clone them.
	 */
	public static TypeReference copyType(TypeReference ref, ASTNode source) {
		if (ref instanceof ParameterizedQualifiedTypeReference) {
			ParameterizedQualifiedTypeReference iRef = (ParameterizedQualifiedTypeReference) ref;
			TypeReference[][] args = null;
			if (iRef.typeArguments != null) {
				args = new TypeReference[iRef.typeArguments.length][];
				int idx = 0;
				for (TypeReference[] inRefArray : iRef.typeArguments) {
					if (inRefArray == null) args[idx++] = null;
					else {
						TypeReference[] outRefArray = new TypeReference[inRefArray.length];
						int idx2 = 0;
						for (TypeReference inRef : inRefArray) {
							outRefArray[idx2++] = copyType(inRef, source);
						}
						args[idx++] = outRefArray;
					}
				}
			}
			TypeReference typeRef = new ParameterizedQualifiedTypeReference(iRef.tokens, args, iRef.dimensions(), iRef.sourcePositions);
			setGeneratedBy(typeRef, source);
			return typeRef;
		}
		
		if (ref instanceof ArrayQualifiedTypeReference) {
			ArrayQualifiedTypeReference iRef = (ArrayQualifiedTypeReference) ref;
			TypeReference typeRef = new ArrayQualifiedTypeReference(iRef.tokens, iRef.dimensions(), iRef.sourcePositions);
			setGeneratedBy(typeRef, source);
			return typeRef;
		}
		
		if (ref instanceof QualifiedTypeReference) {
			QualifiedTypeReference iRef = (QualifiedTypeReference) ref;
			TypeReference typeRef = new QualifiedTypeReference(iRef.tokens, iRef.sourcePositions);
			setGeneratedBy(typeRef, source);
			return typeRef;
		}
		
		if (ref instanceof ParameterizedSingleTypeReference) {
			ParameterizedSingleTypeReference iRef = (ParameterizedSingleTypeReference) ref;
			TypeReference[] args = null;
			if (iRef.typeArguments != null) {
				args = new TypeReference[iRef.typeArguments.length];
				int idx = 0;
				for (TypeReference inRef : iRef.typeArguments) {
					if (inRef == null) args[idx++] = null;
					else args[idx++] = copyType(inRef, source);
				}
			}
			
			TypeReference typeRef = new ParameterizedSingleTypeReference(iRef.token, args, iRef.dimensions(), (long)iRef.sourceStart << 32 | iRef.sourceEnd);
			setGeneratedBy(typeRef, source);
			return typeRef;
		}
		
		if (ref instanceof ArrayTypeReference) {
			ArrayTypeReference iRef = (ArrayTypeReference) ref;
			TypeReference typeRef = new ArrayTypeReference(iRef.token, iRef.dimensions(), (long)iRef.sourceStart << 32 | iRef.sourceEnd);
			setGeneratedBy(typeRef, source);
			return typeRef;
		}
		
		if (ref instanceof Wildcard) {
			Wildcard original = (Wildcard)ref;
			
			Wildcard wildcard = new Wildcard(original.kind);
			wildcard.sourceStart = original.sourceStart;
			wildcard.sourceEnd = original.sourceEnd;
			if (original.bound != null) wildcard.bound = copyType(original.bound, source);
			setGeneratedBy(wildcard, source);
			return wildcard;
		}
		
		if (ref instanceof SingleTypeReference) {
			SingleTypeReference iRef = (SingleTypeReference) ref;
			TypeReference typeRef = new SingleTypeReference(iRef.token, (long)iRef.sourceStart << 32 | iRef.sourceEnd);
			setGeneratedBy(typeRef, source);
			return typeRef;
		}
		
		return ref;
	}
	
	public static Annotation[] copyAnnotations(Annotation[] annotations, ASTNode source) {
		return copyAnnotations(annotations, null, source);
	}
	
	public static Annotation[] copyAnnotations(Annotation[] annotations1, Annotation[] annotations2, ASTNode source) {
		if (annotations1 == null && annotations2 == null) return null;
		if (annotations1 == null) annotations1 = new Annotation[0];
		if (annotations2 == null) annotations2 = new Annotation[0];
		Annotation[] outs = new Annotation[annotations1.length + annotations2.length];
		int idx = 0;
		for (Annotation annotation : annotations1) {
			outs[idx++] = copyAnnotation(annotation, source);
		}
		for (Annotation annotation : annotations2) {
			outs[idx++] = copyAnnotation(annotation, source);
		}
		return outs;
	}
	
	public static Annotation copyAnnotation(Annotation annotation, ASTNode source) {
		int pS = source.sourceStart, pE = source.sourceEnd;
		
		if (annotation instanceof MarkerAnnotation) {
			MarkerAnnotation ann = new MarkerAnnotation(copyType(annotation.type, source), pS);
			setGeneratedBy(ann, source);
			ann.declarationSourceEnd = ann.sourceEnd = ann.statementEnd = pE;
			return ann;
		}
		
		if (annotation instanceof SingleMemberAnnotation) {
			SingleMemberAnnotation ann = new SingleMemberAnnotation(copyType(annotation.type, source), pS);
			setGeneratedBy(ann, source);
			ann.declarationSourceEnd = ann.sourceEnd = ann.statementEnd = pE;
			//TODO memberValue(s) need to be copied as well (same for copying a NormalAnnotation as below).
			ann.memberValue = ((SingleMemberAnnotation)annotation).memberValue;
			return ann;
		}
		
		if (annotation instanceof NormalAnnotation) {
			NormalAnnotation ann = new NormalAnnotation(copyType(annotation.type, source), pS);
			setGeneratedBy(ann, source);
			ann.declarationSourceEnd = ann.statementEnd = ann.sourceEnd = pE;
			ann.memberValuePairs = ((NormalAnnotation)annotation).memberValuePairs;
			return ann;
		}
		
		return annotation;
	}
	
	/**
	 * Checks if the provided annotation type is likely to be the intended type for the given annotation node.
	 * 
	 * This is a guess, but a decent one.
	 */
	public static boolean annotationTypeMatches(Class<? extends java.lang.annotation.Annotation> type, EclipseNode node) {
		if (node.getKind() != Kind.ANNOTATION) return false;
		TypeReference typeRef = ((Annotation)node.get()).type;
		if (typeRef == null || typeRef.getTypeName() == null) return false;
		String typeName = toQualifiedName(typeRef.getTypeName());
		
		TypeLibrary library = new TypeLibrary();
		library.addType(type.getName());
		TypeResolver resolver = new TypeResolver(library, node.getPackageDeclaration(), node.getImportStatements());
		Collection<String> typeMatches = resolver.findTypeMatches(node, typeName);
		
		for (String match : typeMatches) {
			if (match.equals(type.getName())) return true;
		}
		
		return false;
	}
	
	/**
	 * Provides AnnotationValues with the data it needs to do its thing.
	 */
	public static <A extends java.lang.annotation.Annotation> AnnotationValues<A>
			createAnnotation(Class<A> type, final EclipseNode annotationNode) {
		final Annotation annotation = (Annotation) annotationNode.get();
		Map<String, AnnotationValue> values = new HashMap<String, AnnotationValue>();
		
		final MemberValuePair[] pairs = annotation.memberValuePairs();
		for (Method m : type.getDeclaredMethods()) {
			if (!Modifier.isPublic(m.getModifiers())) continue;
			String name = m.getName();
			List<String> raws = new ArrayList<String>();
			List<Object> guesses = new ArrayList<Object>();
			Expression fullExpression = null;
			Expression[] expressions = null;
			
			if (pairs != null) for (MemberValuePair pair : pairs) {
				char[] n = pair.name;
				String mName = n == null ? "value" : new String(pair.name);
				if (mName.equals(name)) fullExpression = pair.value;
			}
			
			boolean isExplicit = fullExpression != null;
			
			if (isExplicit) {
				if (fullExpression instanceof ArrayInitializer) {
					expressions = ((ArrayInitializer)fullExpression).expressions;
				} else expressions = new Expression[] { fullExpression };
				if (expressions != null) for (Expression ex : expressions) {
					StringBuffer sb = new StringBuffer();
					ex.print(0, sb);
					raws.add(sb.toString());
					guesses.add(calculateValue(ex));
				}
			}
			
			final Expression fullExpr = fullExpression;
			final Expression[] exprs = expressions;
			
			values.put(name, new AnnotationValue(annotationNode, raws, guesses, isExplicit) {
				@Override public void setError(String message, int valueIdx) {
					Expression ex;
					if (valueIdx == -1) ex = fullExpr;
					else ex = exprs != null ? exprs[valueIdx] : null;
					
					if (ex == null) ex = annotation;
					
					int sourceStart = ex.sourceStart;
					int sourceEnd = ex.sourceEnd;
					
					annotationNode.addError(message, sourceStart, sourceEnd);
				}
				
				@Override public void setWarning(String message, int valueIdx) {
					Expression ex;
					if (valueIdx == -1) ex = fullExpr;
					else ex = exprs != null ? exprs[valueIdx] : null;
					
					if (ex == null) ex = annotation;
					
					int sourceStart = ex.sourceStart;
					int sourceEnd = ex.sourceEnd;
					
					annotationNode.addWarning(message, sourceStart, sourceEnd);
				}
			});
		}
		
		return new AnnotationValues<A>(type, values, annotationNode);
	}
	
	private static Object calculateValue(Expression e) {
		if (e instanceof Literal) {
			((Literal)e).computeConstant();
			switch (e.constant.typeID()) {
			case TypeIds.T_int: return e.constant.intValue();
			case TypeIds.T_byte: return e.constant.byteValue();
			case TypeIds.T_short: return e.constant.shortValue();
			case TypeIds.T_char: return e.constant.charValue();
			case TypeIds.T_float: return e.constant.floatValue();
			case TypeIds.T_double: return e.constant.doubleValue();
			case TypeIds.T_boolean: return e.constant.booleanValue();
			case TypeIds.T_long: return e.constant.longValue();
			case TypeIds.T_JavaLangString: return e.constant.stringValue();
			default: return null;
			}
		} else if (e instanceof ClassLiteralAccess) {
			return Eclipse.toQualifiedName(((ClassLiteralAccess)e).type.getTypeName());
		} else if (e instanceof SingleNameReference) {
			return new String(((SingleNameReference)e).token);
		} else if (e instanceof QualifiedNameReference) {
			String qName = Eclipse.toQualifiedName(((QualifiedNameReference)e).tokens);
			int idx = qName.lastIndexOf('.');
			return idx == -1 ? qName : qName.substring(idx+1);
		}
		
		return null;
	}
	
	private static Field generatedByField;
	
	static {
		try {
			generatedByField = ASTNode.class.getDeclaredField("$generatedBy");
		} catch (Throwable t) {
			//ignore - no $generatedBy exists when running in ecj.
		}
	}
	
	public static ASTNode getGeneratedBy(ASTNode node) {
		try {
			return (ASTNode) generatedByField.get(node);
		} catch (Exception t) {
			//ignore - no $generatedBy exists when running in ecj.
			return null;
		}
	}
	
	public static boolean isGenerated(ASTNode node) {
		return getGeneratedBy(node) != null;
	}
	
	public static ASTNode setGeneratedBy(ASTNode node, ASTNode source) {
		try {
			generatedByField.set(node, source);
		} catch (Exception t) {
			//ignore - no $generatedBy exists when running in ecj.
		}
		
		return node;
	}
}

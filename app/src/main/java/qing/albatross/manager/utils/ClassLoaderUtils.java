package qing.albatross.manager.utils;

import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ClassLoaderUtils {

  public static final String TAG = "ClassLoaderUtils";

  private static void expandFieldList(Object instance, String fieldName, Object[] extraElements)
      throws NoSuchFieldException, IllegalArgumentException, IllegalAccessException {
    Field jlrField = findField(instance, fieldName);
    Object[] original = ((List<Object>) jlrField.get(instance)).toArray();
    Object[] combined = (Object[]) Array.newInstance(original.getClass().getComponentType(), original.length + extraElements.length);
    System.arraycopy(original, 0, combined, 0, original.length);
    System.arraycopy(extraElements, 0, combined, original.length, extraElements.length);
    jlrField.set(instance, Arrays.asList(combined));
  }

  private static Object[] makeDexElements(Object dexPathList, ArrayList<File> files, File optimizedDirectory,
                                          ArrayList<IOException> suppressedExceptions) throws IllegalAccessException, InvocationTargetException,
      NoSuchMethodException {
    Method makeDexElements;
    if (Build.VERSION.SDK_INT >= 23) {
      makeDexElements = findMethod(dexPathList, "makePathElements", List.class, File.class, List.class);
    } else {
      makeDexElements = findMethod(dexPathList, "makeDexElements", ArrayList.class, File.class, ArrayList.class);
    }
    return (Object[]) makeDexElements.invoke(dexPathList, files, optimizedDirectory, suppressedExceptions);
  }

  private static void expandFieldArray(Object instance, String fieldName,
                                       Object[] extraElements) throws NoSuchFieldException, IllegalArgumentException,
      IllegalAccessException {
    Field jlrField = findField(instance, fieldName);
    Object[] original = (Object[]) jlrField.get(instance);
    Object[] combined = (Object[]) Array.newInstance(original.getClass().getComponentType(), original.length + extraElements.length);
    System.arraycopy(original, 0, combined, 0, original.length);
    System.arraycopy(extraElements, 0, combined, original.length, extraElements.length);
    jlrField.set(instance, combined);
  }


  private static Field findField(Object instance, String name) throws NoSuchFieldException {
    for (Class<?> clazz = instance.getClass(); clazz != null; clazz = clazz.getSuperclass()) {
      try {
        Field field = clazz.getDeclaredField(name);
        if (!field.isAccessible()) {
          field.setAccessible(true);
        }
        return field;
      } catch (NoSuchFieldException ignore) {
      }
    }
    throw new NoSuchFieldException("Field " + name + " not found in " + instance.getClass());
  }


  private static Object[] makeNativeLibraryElement(
      Object dexPathList, ArrayList<File> files)
      throws IllegalAccessException, InvocationTargetException,
      NoSuchMethodException {
    Method makeDexElements =
        findMethod(dexPathList, "makePathElements", List.class);
    return (Object[]) makeDexElements.invoke(dexPathList, files);
  }

  private static Method findMethod(Object instance, String name, Class<?>... parameterTypes)
      throws NoSuchMethodException {
    for (Class<?> clazz = instance.getClass(); clazz != null; clazz = clazz.getSuperclass()) {
      try {
        Method method = clazz.getDeclaredMethod(name, parameterTypes);
        if (!method.isAccessible()) {
          method.setAccessible(true);
        }
        return method;
      } catch (NoSuchMethodException ignore) {
      }
    }
    throw new NoSuchMethodException("Method " + name + " with parameters " +
        Arrays.asList(parameterTypes) + " not found in " + instance.getClass());
  }


  public static void patchClassLoader(ClassLoader loader, String sourceDir, String nativeLibraryDir) throws NoSuchFieldException, IllegalAccessException, InvocationTargetException, NoSuchMethodException {
    Field pathListField = findField(loader, "pathList");
    Object dexPathList = pathListField.get(loader);
    ArrayList<IOException> suppressedExceptions = new ArrayList<>();
    ArrayList<File> files = new ArrayList<>();
    files.add(new File(sourceDir));
    ArrayList<File> nativeLibraries = new ArrayList<>();
    File nativeLibraryFile = new File(nativeLibraryDir);
    nativeLibraries.add(nativeLibraryFile);
    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) {
      expandFieldList(dexPathList, "nativeLibraryDirectories", new File[]{nativeLibraryFile});
      expandFieldArray(dexPathList, "nativeLibraryPathElements", makeNativeLibraryElement(dexPathList, nativeLibraries));
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      expandFieldList(dexPathList, "nativeLibraryDirectories", new File[]{nativeLibraryFile});
      expandFieldArray(dexPathList, "nativeLibraryPathElements", makeDexElements(dexPathList, nativeLibraries, null, suppressedExceptions));
    } else {
      expandFieldArray(dexPathList, "nativeLibraryDirectories", new File[]{nativeLibraryFile});
    }
    expandFieldArray(dexPathList, "dexElements", makeDexElements(dexPathList, files, null, suppressedExceptions));
    if (!suppressedExceptions.isEmpty()) {
      for (IOException e : suppressedExceptions) {
        Log.w(TAG, "Exception in makeDexElement", e);
      }
      Field suppressedExceptionsField = findField(loader, "dexElementsSuppressedExceptions");
      IOException[] dexElementsSuppressedExceptions = (IOException[]) suppressedExceptionsField.get(loader);
      if (dexElementsSuppressedExceptions == null) {
        dexElementsSuppressedExceptions = suppressedExceptions.toArray(new IOException[0]);
      } else {
        IOException[] combined = new IOException[suppressedExceptions.size() + dexElementsSuppressedExceptions.length];
        suppressedExceptions.toArray(combined);
        System.arraycopy(dexElementsSuppressedExceptions, 0, combined, suppressedExceptions.size(), dexElementsSuppressedExceptions.length);
        dexElementsSuppressedExceptions = combined;
      }
      suppressedExceptionsField.set(loader, dexElementsSuppressedExceptions);
    }
  }

}

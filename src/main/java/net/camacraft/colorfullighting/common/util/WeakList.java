package net.camacraft.colorfullighting.common.util;

import org.jetbrains.annotations.NotNull;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.function.Consumer;

public class WeakList<T> implements List<T> {
	List<WeakReference<T>> raw;
	
	public WeakList(List<WeakReference<T>> raw) {
		this.raw = raw;
	}
	
	@Override
	public int size() {
		return raw.size();
	}
	
	@Override
	public boolean isEmpty() {
		return raw.isEmpty();
	}
	
	@Override
	public boolean contains(Object o) {
		for (WeakReference<T> t : raw) {
			Object ot = t.get();
			if (ot == o) return true;
			if (ot == null) continue;
			if (ot.equals(o)) return true;
		}
		return false;
	}
	
	@Override
	public @NotNull Object[] toArray() {
		throw new RuntimeException("TODO");
	}
	
	@Override
	public @NotNull <T> T[] toArray(@NotNull T[] a) {
		throw new RuntimeException("TODO");
	}
	
	@Override
	public boolean add(T tWeakReference) {
		return raw.add(new WeakReference<>(tWeakReference));
	}
	
	@Override
	public boolean remove(Object o) {
		int idx = indexOf(o);
		if (idx != -1) {
			raw.remove(idx);
			return true;
		}
		return false;
	}
	
	@Override
	public boolean containsAll(@NotNull Collection<?> c) {
		for (Object o : c) {
			if (!contains(o))
				return false;
		}
		return true;
	}
	
	@Override
	public boolean addAll(@NotNull Collection<? extends T> c) {
		for (T tWeakReference : c) {
			add(tWeakReference);
		}
		return true;
	}
	
	@Override
	public boolean addAll(int index, @NotNull Collection<? extends T> c) {
		for (T tWeakReference : c) {
			add(tWeakReference);
		}
		return true;
	}
	
	@Override
	public boolean removeAll(@NotNull Collection<?> c) {
		for (Object o : c) {
			remove(o);
		}
		return true;
	}
	
	@Override
	public boolean retainAll(@NotNull Collection<?> c) {
		throw new RuntimeException("Unsupported");
	}
	
	@Override
	public void clear() {
		raw.clear();
	}
	
	@Override
	public T get(int index) {
		return raw.get(index).get();
	}
	
	@Override
	public T set(int index, T element) {
		WeakReference<T> old = raw.set(index, new WeakReference<>(element));
		if (old == null) return null;
		return old.get();
	}
	
	@Override
	public void add(int index, T element) {
		raw.add(index, new WeakReference<>(element));
	}
	
	@Override
	public T remove(int index) {
		WeakReference<T> old = raw.remove(index);
		if (old == null) return null;
		return old.get();
	}
	
	@Override
	public int indexOf(Object o) {
		for (int i = 0; i < raw.size(); i++) {
			WeakReference<T> t = raw.get(i);
			Object ot = t.get();
			if (ot == o) return i;
			if (ot == null) continue;
			if (ot.equals(o)) return i;
		}
		return -1;
	}
	
	@Override
	public int lastIndexOf(Object o) {
		for (int i = raw.size() - 1; i >= 0; i--) {
			WeakReference<T> t = raw.get(i);
			Object ot = t.get();
			if (ot == o) return i;
			if (ot == null) continue;
			if (ot.equals(o)) return i;
		}
		return -1;
	}
	
	@Override
	public @NotNull Iterator<T> iterator() {
		return new WeakIterator(raw.iterator());
	}
	
	@Override
	public @NotNull ListIterator<T> listIterator() {
		return new WeakListIterator(raw.listIterator());
	}
	
	@Override
	public @NotNull ListIterator<T> listIterator(int index) {
		return new WeakListIterator(raw.listIterator(index));
	}
	
	@Override
	public @NotNull List<T> subList(int fromIndex, int toIndex) {
		return new WeakList<>(raw.subList(fromIndex, toIndex));
	}
	
	class WeakIterator implements Iterator<T> {
		Iterator<WeakReference<T>> rawIterator;
		
		public WeakIterator(Iterator<WeakReference<T>> rawIterator) {
			this.rawIterator = rawIterator;
		}
		
		@Override
		public boolean hasNext() {
			return rawIterator.hasNext();
		}
		
		@Override
		public T next() {
			WeakReference<T> t = rawIterator.next();
			if (t == null) return null;
			return t.get();
		}
		
		@Override
		public void remove() {
			rawIterator.remove();
		}
		
		@Override
		public void forEachRemaining(Consumer<? super T> action) {
			rawIterator.forEachRemaining((t) -> {
				if (t == null) {
					action.accept(null);
				} else {
					action.accept(t.get());
				}
			});
		}
	}
	
	class WeakListIterator implements ListIterator<T> {
		ListIterator<WeakReference<T>> rawIterator;
		
		public WeakListIterator(ListIterator<WeakReference<T>> rawIterator) {
			this.rawIterator = rawIterator;
		}
		
		@Override
		public boolean hasNext() {
			return rawIterator.hasNext();
		}
		
		@Override
		public T next() {
			WeakReference<T> t = rawIterator.next();
			if (t == null) return null;
			return t.get();
		}
		
		@Override
		public void remove() {
			rawIterator.remove();
		}
		
		@Override
		public void forEachRemaining(Consumer<? super T> action) {
			rawIterator.forEachRemaining((t) -> {
				if (t == null) {
					action.accept(null);
				} else {
					action.accept(t.get());
				}
			});
		}
		
		@Override
		public boolean hasPrevious() {
			return rawIterator.hasPrevious();
		}
		
		@Override
		public T previous() {
			WeakReference<T> t = rawIterator.previous();
			if (t == null) return null;
			return t.get();
		}
		
		@Override
		public int nextIndex() {
			return rawIterator.nextIndex();
		}
		
		@Override
		public int previousIndex() {
			return rawIterator.previousIndex();
		}
		
		@Override
		public void set(T t) {
			rawIterator.set(new WeakReference<>(t));
		}
		
		@Override
		public void add(T t) {
			rawIterator.add(new WeakReference<>(t));
		}
	}
	
	public void prune() {
		List<Integer> remove = new ArrayList<>();
		for (int i = 0; i < this.size(); i++) {
			T t = get(i);
			if (t == null) remove.add(i);
		}
		
		if (!remove.isEmpty()) {
			System.out.println("Removing " + remove.size() + " dead references");
		}
		
		int offset = 0;
		for (int i : remove) {
			remove(i - offset);
			offset++;
		}
	}
}

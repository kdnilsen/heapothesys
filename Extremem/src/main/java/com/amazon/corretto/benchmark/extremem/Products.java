// Copyright Amazon.com, Inc. or its affiliates.  All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package com.amazon.corretto.benchmark.extremem;

import java.util.TreeMap;

/**
 * Keep track of all currently active products.
 */
class Products extends ExtrememObject {
  static class ChangeLogNode {
    private Product replacement_product;
    private int replacement_index;
    private ChangeLogNode next;

    ChangeLogNode(int index, Product product) {
      this.replacement_index = index;
      this.replacement_product = product;
      this.next = null;
    }

    int index() {
      return replacement_index;
    }

    Product product() {
      return replacement_product;
    }
  }

  static class ChangeLog {
    ChangeLogNode head, tail;

    ChangeLog() {
      head = tail = null;
    }

    synchronized private void addToEnd(ChangeLogNode node) {
      if (head == null) {
        head = tail = node;
      } else {
        tail.next = node;
        tail = node;
      }
    }

    void append(int index, Product product) {
      ChangeLogNode new_node = new ChangeLogNode(index, product);
      addToEnd(new_node);
    }

    // Returns null if ChangeLog is empty.
    synchronized ChangeLogNode pull() {
      ChangeLogNode result = head;
      if (head == tail) {
        // This handles case where head == tail == null already.  Overwriting with null is cheaper than testing and branching
        // over for special case.
        head = tail = null;
      } else {
        head = head.next;
      }
      return result;
    }
  }

  static class CurrentProductsData {
    final private ArrayletOflong product_ids;
    // Map unique product id to Product
    final private TreeMap <Long, Product> product_map;
    // Map keywords found in product name to product id
    final private TreeMap <String, ExtrememHashSet<Long>> name_index;
    // Map keywords found in product description to product id
    final private TreeMap <String, ExtrememHashSet<Long>> description_index;

    CurrentProductsData(ArrayletOflong product_ids, TreeMap <Long, Product> product_map,
                        TreeMap <String, ExtrememHashSet<Long>> name_index,
                        TreeMap <String, ExtrememHashSet<Long>> description_index) {

      this.product_ids = product_ids;
      this.product_map = product_map;
      this.name_index = name_index;
      this.description_index = description_index;
    }

    ArrayletOflong productIds() {
      return product_ids;
    }

    TreeMap <Long, Product> productMap() {
      return product_map;
    }

    TreeMap <String, ExtrememHashSet<Long>> nameIndex() {
      return name_index;
    }

    TreeMap <String, ExtrememHashSet<Long>> descriptionIndex() {
      return description_index;
    }
  }

  private final boolean do_fast_match_all;

  // The change_log is only used if config.PhasedUpdates
  final ChangeLog change_log;

  private static final float DefaultLoadFactor = 0.75f;

  /* Concurrency control:
   *  Changes to these state variables occur only within the
   *  constructor or while holding the exclusive write lock
   *  implemented by cc.
   *
   *  Reading of these state variables occurs only while holding the
   *  exlusive write lock or a concurrent-access read locak
   *  implemented by cc.
   */

  private long pncl;            // product name cumulative length: The sum
                                // of all product name string lengths.
                                // Except for brief moments of transition
                                // that are protected by the mutual
                                // exclusion write lock, the number of
                                // products is config.NumProducts().
  
  private long pdcl;            // product description cumulative
                                // length: The sum of all product
                                // description string lengths.
                                // Except for brief moments of transition
                                // that are protected by the mutual
                                // exclusion write lock, the number of
                                // products is config.NumProducts().
  
  private int nie;              // name_index entries: This is the
                                // number of entries in the
                                // name_index, aka the number of
                                // unique words ever apearing in any
                                // product name.  (Index entries are
                                // never removed after they have been
                                // inserted into the name_index,
                                // though the size of the associated
                                // HashSet may shrink to zero.)

  private long nicl;            // name_index cumulative length: This 
                                // is the combined length of all nie
                                // strings that comprise the name
                                // index.  Though these strings are a
                                // subset of strings contained in the
                                // Configuration dictionary, they are
                                // independently constructed for
                                // purposes of building the index so
                                // are accounted separately. 

  private int nip;              // name_index products: This is
                                // the total number of product entries
                                // represented within the HashSet
                                // content entries of name_index.
                                // Note that many products appear
                                // multiple times within name_index,
                                // each appearance associated with a
                                // different name keyword.  For this
                                // reason, nip is typically greater
                                // than config.NumProducts(). 

  private long nihacl;          // name_index hash array cumulative
                                // length: cumulative length of all
                                // arrays associated with instances of
                                // HashSet<Long> which are the content
                                // of name_index.

  private int die;              // description_index entries: This is
                                // the number of entries in the
                                // description_index, aka the number
                                // of unique words appearing in all
                                // product descriptions.  (Index entries
                                // are never removed after they have
                                // been inserted into description_index,
                                // though the size of the associated
                                // HashSet may shrink to zero.)

  private long dicl;            // description_index cumulative length:
                                // This is the combined length of all
                                // die strings that comprise the
                                // description index.  Though these
                                // strings are a subset of strings
                                // contained in the Configuration
                                // dictionary, they are independently
                                // constructed for purposes of
                                // building the index so are accounted
                                // separately. 

  private int dip;              // description_index products: This is
                                // the total number of product entries
                                // represented within the HashSet
                                // content entries of description_index.
                                // Note that many products appear
                                // multiple times within
                                // description_index, each appearance
                                // associated with a different
                                // description keyword.  For this
                                // reason, dip is typically greater
                                // than config.NumProducts().
  
  private long dihacl;          // description_index hash array
                                // cumulative length: cumulative
                                // length of all arrays associated
                                // with instances of HashSet<Long>
                                // which are the content of the
                                // description index.

  private long npi;             // next product id

  private ArrayletOflong product_ids;

  private ConcurrencyControl cc;
  private Configuration config;

  // Map unique product id to Product
  private TreeMap <Long, Product> product_map;
  // Map keywords found in product name to product id
  private TreeMap <String, ExtrememHashSet<Long>> name_index;
  // Map keywords found in product description to product id
  private TreeMap <String, ExtrememHashSet<Long>> description_index;

  Products (ExtrememThread t, LifeSpan ls, Configuration config) {
    super(t, ls);
    this.do_fast_match_all = !config.AllowAnyMatch();

    if (config.PhasedUpdates()) {
      change_log = new ChangeLog();
    } else {
      change_log = null;
    }

    MemoryLog log = t.memoryLog();
    MemoryLog garbage = t.garbageLog();
    Polarity Grow = Polarity.Expand;
    int num_products = config.NumProducts();

    this.config = config;
    product_ids = new ArrayletOflong(t, ls,
                                     config.MaxArrayLength(), num_products);
    product_map = new TreeMap<Long, Product>();
    name_index = new TreeMap<String, ExtrememHashSet<Long>>();
    description_index = new TreeMap<String, ExtrememHashSet<Long>>();

    cc = new ConcurrencyControl (t, ls);
    for (int i = 0; i < num_products; i++) {
      String name = randomName(t);
      pncl += name.length();
      String description = randomDescription(t);
      pdcl += description.length();

      Trace.msg(4, "creating product with name: ", name);
      Trace.msg(4, " description: ", description);

      // name and description, allocated above as Ephemeral, are
      // converted to LifeSpan.NearlyForever within Product's
      // constructor.  Hereafter, they are presumed to be part of the
      // Product representation (and their memory is reclaimed when
      // Product's memory is reclaimed).  Do not reclaim their memory
      // here. 
      Product new_product = new Product(t, LifeSpan.NearlyForever,
                                        npi, name, description);
      long id = npi++;
      product_ids.set(i, id);
      // id is autoboxed to Long below.  Accounting for this Long is
      // tabulated further below.
      product_map.put(id, new_product);
      addToIndices(t, new_product);
    }

    // Account for 4 int fields: nie, nip, die, dip;
    // 7 long fields: pncl, nicl, pdcl, dicl, npi, nihacl, dihacl.
    log.accumulate(ls, MemoryFlavor.ObjectRSB, Grow,
                   7 * Util.SizeOfLong + 4 * Util.SizeOfInt);

    // Account for 6 reference fields: product_ids, cc, config, product_map,
    // name_index, description_index.
    log.accumulate(ls, MemoryFlavor.ObjectReference, Grow, 6);

    // Memory referenced from product_ids accounted at alloation time
    
    // Account for object referenced by product_map.
    Util.tallyTreeMap(num_products, log, ls, Grow);

    // Each key within product_map is a Long object, with a single long field
    log.accumulate(ls, MemoryFlavor.PlainObject, Grow, num_products);
    log.accumulate(ls, MemoryFlavor.ObjectRSB, Grow,
                   num_products * Util.SizeOfLong);
    // The content of each product_map entry is a Product object,
    // memory for which is already accounted for.

    // Account for objects referenced by name_index.  Keyword
    // strings used in name_index are distinct from the words
    // contained within config's dictionary, so account for them here.
    Util.tallyTreeMap(nie, log, ls, Grow);
    // Each key within name_index is a uniquely constructed String.
    // Each content within name_index is an ExtrememHashSet of Long.  The
    // memory for both keys and content of entries is accounted during
    // construction of name_index.

    // Account for objects referenced by description_index.  Keyword
    // strings used in description_index are distinct from the words
    // contained within config's dictionary, so account for them here.
    Util.tallyTreeMap(die, log, ls, Grow);
    // Each key within description_index is a uniquely constructed String.
    // Each content within description_index is an ExtrememHashSet of
    // Long.  The memory for both keys and content of entries is
    // accounted during construction of description_index.
  }

  // In PhasedUpdates mode of operation, the database updater thread invokes this service to update customer_names
  // and customer_map each time it rebuilds the Customers database
  synchronized void establishUpdatedDataBase(ExtrememThread t, ArrayletOflong product_ids, TreeMap <Long, Product> product_map,
                                             TreeMap <String, ExtrememHashSet<Long>> name_index,
                                             TreeMap <String, ExtrememHashSet<Long>> description_index) {
    this.product_ids = product_ids;
    this.product_map = product_map;
    this.name_index = name_index;
    this.description_index = description_index;
  }

  // In PhasedUpdates mode of operation. every CustomerThread invokes getUpdatedDataBase() before each customer transaction.
  // This assures no incoherent changes to data base in the middle of the customer transaction.
  synchronized CurrentProductsData getUpdatedDataBase() {
    if (config.PhasedUpdates()) {
      return new CurrentProductsData(product_ids, product_map, name_index, description_index);
    } else {
      throw new IllegalStateException("Only update data base in PhasedUpdates mode of operation");
    }
  }

  // For PhasedUpdates mode of operation
  Product fetchProductByIndexPhasedUpdates(ExtrememThread t, int index, CurrentProductsData current) {
    long id = current.productIds().get(index);
    return current.productMap().get(id);
  }

  Product fetchProductByIndex(ExtrememThread t, int index) {
    if (config.FastAndFurious()) {
      long id;
      Product result;
      synchronized (product_ids) {
        id = product_ids.get(index);
      }
      if (id == -1) {
        result = null;
      } else {
        synchronized (product_map) {
          result = product_map.get(id);
        }
      }
      return result;
    } else {
      ProductSelector ps = new ProductSelector(t, index, this);
      cc.actAsReader(ps);
      ps.garbageFootprint(t);
      return ps.result;
    }
  }

  Product controlledFetchProductByIndex(ExtrememThread t, int index) {
    long id = product_ids.get(index);
    return product_map.get(id);
  }

  // The Product result returned is only an approximation of which Product will be replaced.  In the case that
  // the change_log holds multiple changes of the same product, this replacement will change the product that
  // is in the change_log rather than the one that is in the current data base.
  Product replaceArbitraryProductPhasedUpdates(ExtrememThread t, Product new_product) {
    int index = t.randomUnsignedInt() % product_ids.length();
    change_log.append(index, new_product);
    long old_id = product_ids.get(index);
    Product removed_product_approximation = product_map.get(old_id);
    return removed_product_approximation;
  }

  Product replaceArbitraryProduct(ExtrememThread t, Product new_product) {
    if (config.FastAndFurious()) {
      long old_id;
      int index;
      do {
        // Take care to avoid races in case another thread tries to replace Product at same index.  Collisions are expected
        // to be very rare.
        index = t.randomUnsignedInt() % product_ids.length();
        synchronized(product_ids) {
          old_id = product_ids.get(index);
          product_ids.set(index, -1L); // This might be redundant.
        }
      } while (old_id == -1L);

      Product removed_product;
      synchronized (product_map) {
        removed_product = product_map.remove(old_id);
      }

      // Though garbage collection of removed_product may be deferred
      // until any pending BrowsingHistory and SalesTransaction
      // instances that reference removed_product become garbage
      // themselves, account for the object's garbage collection here.
      removed_product.garbageFootprint(t);
      Util.abandonTreeNode(t, this.intendedLifeSpan());
      synchronized (this) {
        pncl -= removed_product.name().length();
        pdcl -= removed_product.description().length();
      }
      removed_product.deactivate();

      // Remove the obsolete product info.
      rmFromIndicesFastAndFurious(t, removed_product);

      synchronized (product_ids) {
        product_ids.set(index, new_product.id());
      }
      synchronized (this) {
        pncl += new_product.name().length();
        pdcl += new_product.description().length();
      }
      synchronized (product_map) {
        product_map.put(new_product.id(), new_product);
      }
      Util.createTreeNode(t, this.intendedLifeSpan());

      // Add the new product into the keyword indices.
      addToIndicesFastAndFurious(t, new_product);

      return removed_product;
    } else {
      ProductReplacer pr = new ProductReplacer(t, this, new_product);
      cc.actAsWriter (pr);
      pr.garbageFootprint(t);
      return pr.removed_product;
    }
  }

  Product controlledReplaceArbitraryProduct (ExtrememThread t,
                                             Product product) {
    int index = t.randomUnsignedInt() % product_ids.length();
    long old_id = product_ids.get(index);
    product_ids.set(index, product.id());

    Product removed_product = product_map.remove(old_id);

    // Though garbage collection of removed_product may be deferred
    // until any pending BrowsingHistory and SalesTransaction
    // instances that reference removed_product become garbage
    // themselves, account for the object's garbage collection here.
    removed_product.garbageFootprint(t);
    Util.abandonTreeNode(t, this.intendedLifeSpan());

    pncl -= removed_product.name().length();
    pdcl -= removed_product.description().length();

    removed_product.deactivate();

    pncl += product.name().length();
    pdcl += product.description().length();

    product_map.put(product.id(), product);
    Util.createTreeNode(t, this.intendedLifeSpan());

    // Remove the obsolete product info.
    rmFromIndices(t, removed_product);

    // Add the new product into the keyword indices.
    addToIndices(t, product);

    return removed_product;
  }

  public void replaceRandomProduct(ExtrememThread t) {
    String name = randomName(t);
    String description = randomDescription(t);
    long new_id = nextUniqId ();
    Product new_product = new Product(t, LifeSpan.NearlyForever,
                                      new_id, name, description);
    if (config.PhasedUpdates()) {
      Product old_product = replaceArbitraryProductPhasedUpdates(t, new_product);

    } else {
      Product old_product = replaceArbitraryProduct (t, new_product);
      Trace.msg(4, "old product: ", old_product.name(),
                " replaced with new product: ", new_product.name());
    }

    // Note that there is a race between when keyword searches are
    // performed and when products are looked up.  For example, a
    // product lookup may succeed, but by the time a customer is ready
    // to place an order, or by the time a placed SalesTransaction is
    // actually posted, the product will have been decommissioned.
    // This race is handled elsewhere.
  }

  /**
   * Compute the intersection of sets represented by all_matches[0..all_count-1] and new_matches[0..length()-1]
   *
   * On entry, all_matches and new_matches are sorted in increasing order.
   *
   * On return, valid entries (entries that were present in both arrays on entry) of the all_matches[] array
   * are sifted to the front of that array.  The return value represents the number of valid entries within
   * the union of the two arrays.
   */
  int filter_out(int all_count, long all_matches[], long new_matches[]) {
    int all_idx = 0;
    int slide_idx = 0;
    int new_idx = 0;
    int new_length = new_matches.length;

    while (all_idx < all_count) {
      long next_all_candidate = all_matches[all_idx];
      while ((new_idx < new_length) && (new_matches[new_idx] < next_all_candidate)) {
        new_idx++;
      }
      if ((new_idx < new_length) && (new_matches[new_idx] == next_all_candidate)) {  // Preserve this value
	if (slide_idx < new_idx) {
          all_matches[slide_idx++] = all_matches[all_idx++];
	} else {
	  slide_idx++;
	  all_idx++;
	}
      } else {  // discard this value from all_matches because it's not present in new_matches array
	all_idx++;
      }
    }
    return slide_idx;
  }

  int partition(long array[], int first, int last) {
    long pivot = array[last];
    int i = first - 1;
    for (int j = first; j < last; j++) {
      if (array[j] <= pivot) {
	i++;
	long tmp = array[i];
	array[i] = array[j];
	array[j] = tmp;
      }
    }
    long tmp = array[i+1];
    array[i + 1] = array[last];
    array[last] = tmp;
    return i + 1;
  }

  void quicksort(long array[], int first, int last) {
    if (first < last) {
      int partition_index = partition(array, first, last);
      quicksort(array, first, partition_index - 1);
      quicksort(array, partition_index + 1, last);
    }
  }

  void dumpArray(String msg, long array[], int length) {
      Report.output();
      Report.outputNoLine(msg, ": dumpArray[", Integer.toString(length), "] ");
      for (int i = 0; i < length; i++) {
	  Report.outputNoLine(Long.toString(array[i]), " ");
      }
      Report.output();
  }

  Product[] lookupProductsMatchingAllPhasedUpdates(ExtrememThread t, String[] keywords, CurrentProductsData current) {
    Product[] result;

    // Note: For proper tally of allocated memory, we should abandon name_matched_ids and description_matched_ids
    // within each iteration of loop.
    if (this.do_fast_match_all) {
      int intersection_size = 0;
      long all_matches[] = null;
      for (int i = 0; i < keywords.length; i++) {
        String keyword = keywords[i];

        ExtrememHashSet<Long> name_matched_ids;
        name_matched_ids = current.nameIndex().get(keyword);
        int name_matches = (name_matched_ids == null)? 0: name_matched_ids.size();

	ExtrememHashSet<Long> description_matched_ids;
	description_matched_ids = current.descriptionIndex().get(keyword);
        int description_matches = (description_matched_ids == null)? 0: description_matched_ids.size();

	if (i == 0) {
          intersection_size = name_matches + description_matches;
	  Util.ephemeralRSBArray(t, intersection_size, Util.SizeOfLong);
	  all_matches = new long[intersection_size];
	  int dest_idx = 0;
	  Util.createEphemeralHashSetIterator(t);
          if (name_matched_ids != null) {
            for (Long id: name_matched_ids) {
              all_matches[dest_idx++] = id.longValue();
            }
          }
	  Util.abandonEphemeralHashSetIterator(t);
	  Util.createEphemeralHashSetIterator(t);
          if (description_matched_ids != null) {
            for (Long id: description_matched_ids) {
              all_matches[dest_idx++] = id.longValue();
            }
          }
	  Util.abandonEphemeralHashSetIterator(t);
	  //	  dumpArray("Before sorting initial array", all_matches, intersection_size);
	  quicksort(all_matches, 0, intersection_size - 1);
	  //	  dumpArray("After sorting initial array", all_matches, intersection_size);
	} else {
          int new_matches_size = name_matches + description_matches;
	  Util.ephemeralRSBArray(t, new_matches_size, Util.SizeOfLong);
	  long new_matches[] = new long[new_matches_size];
	  int dest_idx = 0;
	  Util.createEphemeralHashSetIterator(t);
          if (name_matched_ids != null) {
            for (Long id: name_matched_ids) {
              new_matches[dest_idx++] = id.longValue();
            }
          }
	  Util.abandonEphemeralHashSetIterator(t);
	  Util.createEphemeralHashSetIterator(t);
          if (description_matched_ids != null) {
            for (Long id: description_matched_ids) {
              new_matches[dest_idx++] = id.longValue();
            }
          }
	  Util.abandonEphemeralHashSetIterator(t);
	  //	  dumpArray("Before sorting supplemental array", new_matches, new_matches_size);
	  quicksort(new_matches, 0, new_matches_size - 1);
	  //	  dumpArray("After sorting supplemental array", new_matches, new_matches_size);
	  intersection_size = filter_out(intersection_size, all_matches, new_matches);
	  //      dumpArray("After filtering original array", all_matches, intersection_size);
	  Util.abandonEphemeralRSBArray(t, new_matches_size, Util.SizeOfLong);
	}
      }
      result = new Product[intersection_size];
      Util.ephemeralReferenceArray(t, intersection_size);
      int cancel_count = 0;
      for (int i = 0; i < intersection_size; i++) {
	result[i] = product_map.get(all_matches[i]);
	if (result[i] == null) {
	  cancel_count++;
	}
      }
      Util.abandonEphemeralRSBArray(t, all_matches.length, Util.SizeOfLong);
      // In case any products have been cancelled during our concurrent lookup, filter them from the result
      if (cancel_count > 0) {
	Product original_result[] = result;
	Util.ephemeralReferenceArray(t, intersection_size - cancel_count);
	result = new Product[intersection_size - cancel_count];
	int i = 0;
	for (int j = 0; j < intersection_size; j++) {
	  if (original_result[j] != null) {
	    result[i++] = original_result[j];
	  }
	}
	Util.abandonEphemeralReferenceArray(t, intersection_size);
      }
    } else {
      // This is the original implementation.  It was written to "make work" and create garbage.  For some workloads,
      // however, this creates so much work that GC interference gets lost in the noise.  With set sizes of 5,000,
      // the time spent here is tens of ms.
      ExtrememHashSet<Product> intersection = new ExtrememHashSet<Product>(t, LifeSpan.Ephemeral);
      for (int i = 0; i < keywords.length; i++) {
	String keyword = keywords[i];

	if (i == 0) {
	  ExtrememHashSet<Long> matched_ids;
	  matched_ids = current.nameIndex().get(keyword);
	  if (matched_ids != null) {
	    Util.createEphemeralHashSetIterator(t);
	    for (Long id: matched_ids) {
	      addToSetIfAvailable(t, intersection, id);
	    }
	    Util.abandonEphemeralHashSetIterator(t);
	  }

	  matched_ids = current.descriptionIndex().get(keyword);
	  if (matched_ids != null) {
	    Util.createEphemeralHashSetIterator(t);
	    for (Long id: matched_ids) {
		addToSetIfAvailable(t, intersection, id);
	    }
	    Util.abandonEphemeralHashSetIterator(t);
	  }
	} else {
	  ExtrememHashSet<Long> matched_ids;
	  ExtrememHashSet<Product> new_matches = new ExtrememHashSet<Product>(t, LifeSpan.Ephemeral);
	  matched_ids = current.nameIndex().get(keyword);
	  if (matched_ids != null) {
	    Util.createEphemeralHashSetIterator(t);
	    for (Long id: matched_ids) {
	      addToSetIfAvailable(t, new_matches, id);
	    }
	    Util.abandonEphemeralHashSetIterator(t);
	  }

	  matched_ids = current.descriptionIndex().get(keyword);
	  if (matched_ids != null) {
	    Util.createEphemeralHashSetIterator(t);
	    for (Long id: matched_ids) {
	      addToSetIfAvailable(t, new_matches, id);
	    }
	    Util.abandonEphemeralHashSetIterator(t);
	  }
	  ExtrememHashSet<Product> remove_set = new ExtrememHashSet<Product>(t, LifeSpan.Ephemeral);
	  Util.createEphemeralHashSetIterator(t);
	  for (Product p: intersection) {
	    if (!new_matches.contains(p)) {
	      remove_set.add(t, p);
	    }
	  }
	  Util.abandonEphemeralHashSetIterator(t);
	  new_matches.garbageFootprint(t);
	  Util.createEphemeralHashSetIterator(t);
	  for (Product p: remove_set) {
	    intersection.remove(t, p);
	  }

	  Util.abandonEphemeralHashSetIterator(t);
	  remove_set.garbageFootprint(t);
	  if (intersection.size() == 0) {
	    Util.ephemeralReferenceArray(t, 0);
	    // Returning an array with no entries.
	    return new Product[0];
	  }
	}
      }
      result = new Product[intersection.size()];
      Util.ephemeralReferenceArray(t, result.length);
      int j = 0;
      Util.createEphemeralHashSetIterator(t);
      for (Product p: intersection) {
	result[j++] = p;
      }
      Util.abandonEphemeralHashSetIterator(t);
      intersection.garbageFootprint(t);
    }
    return result;
  }

  Product[] lookupProductsMatchingAllFastAndFurious(ExtrememThread t, String [] keywords) {
    if (this.do_fast_match_all) {
      int intersection_size = 0;
      long all_matches[] = null;
      for (int i = 0; i < keywords.length; i++) {
        String keyword = keywords[i];

        ExtrememHashSet<Long> name_matched_ids;
	synchronized (name_index) {
	  name_matched_ids = name_index.get(keyword);
	}
        int name_matches = (name_matched_ids == null)? 0: name_matched_ids.size();
	ExtrememHashSet<Long> description_matched_ids;
	synchronized (description_index) {
	  description_matched_ids = description_index.get(keyword);
	}
        int description_matches = (description_matched_ids == null)? 0: description_matched_ids.size();
	if (i == 0) {
	  intersection_size = name_matched_ids.size() + description_matched_ids.size();
	  Util.ephemeralRSBArray(t, intersection_size, Util.SizeOfLong);

	  all_matches = new long[intersection_size];
	  int dest_idx = 0;
	  Util.createEphemeralHashSetIterator(t);
	  if (name_matched_ids != null) {
            synchronized(name_matched_ids) {
              for (Long id: name_matched_ids) {
                all_matches[dest_idx++] = id.longValue();
              }
            }
          }
	  Util.abandonEphemeralHashSetIterator(t);
	  Util.createEphemeralHashSetIterator(t);
          if (description_matched_ids != null) {
            synchronized(description_matched_ids) {
              for (Long id: description_matched_ids) {
                all_matches[dest_idx++] = id.longValue();
              }
            }
          }
	  Util.abandonEphemeralHashSetIterator(t);
	  //	  dumpArray("Before sorting initial array", all_matches, intersection_size);
	  quicksort(all_matches, 0, intersection_size - 1);
	  //	  dumpArray("After sorting initial array", all_matches, intersection_size);
	} else {
	  int new_matches_size = name_matches + description_matches;
	  Util.ephemeralRSBArray(t, new_matches_size, Util.SizeOfLong);
	  long new_matches[] = new long[new_matches_size];
	  int dest_idx = 0;
	  Util.createEphemeralHashSetIterator(t);
          if (name_matched_ids != null) {
            synchronized(name_matched_ids) {
              for (Long id: name_matched_ids) {
                new_matches[dest_idx++] = id.longValue();
              }
            }
          }
	  Util.abandonEphemeralHashSetIterator(t);
	  Util.createEphemeralHashSetIterator(t);
          if (description_matched_ids != null) {
            synchronized(description_matched_ids) {
              for (Long id: description_matched_ids) {
                new_matches[dest_idx++] = id.longValue();
              }
            }
          }
	  Util.abandonEphemeralHashSetIterator(t);
	  quicksort(new_matches, 0, new_matches_size - 1);
	  intersection_size = filter_out(intersection_size, all_matches, new_matches);
	  Util.abandonEphemeralRSBArray(t, new_matches_size, Util.SizeOfLong);
	}
      }
      Product[] result = new Product[intersection_size];
      Util.ephemeralReferenceArray(t, intersection_size);
      int cancel_count = 0;
      for (int i = 0; i < intersection_size; i++) {
	result[i] = product_map.get(all_matches[i]);
	if (result[i] == null) {
	  cancel_count++;
	}
      }
      Util.abandonEphemeralRSBArray(t, all_matches.length, Util.SizeOfLong);
      // In case any products have been cancelled during our concurrent lookup, filter them from the result
      if (cancel_count > 0) {
	Product original_result[] = result;
	Util.ephemeralReferenceArray(t, intersection_size - cancel_count);
	result = new Product[intersection_size - cancel_count];
	int i = 0;
	for (int j = 0; j < intersection_size; j++) {
	  if (original_result[j] != null) {
	    result[i++] = original_result[j];
	  }
	}
	Util.abandonEphemeralReferenceArray(t, intersection_size);
      }
      return result;
    } else {
      ExtrememHashSet<Product> intersection = new ExtrememHashSet<Product>(t, LifeSpan.Ephemeral);
      for (int i = 0; i < keywords.length; i++) {
	String keyword = keywords[i];
	if (i == 0) {
	  ExtrememHashSet<Long> matched_ids;
	  synchronized (name_index) {
	    matched_ids = name_index.get(keyword);
	  }
	  if (matched_ids != null) {
	    Util.createEphemeralHashSetIterator(t);
	    synchronized (matched_ids) {
	      for (Long id: matched_ids) {
		addToSetIfAvailable(t, intersection, id);
	      }
	    }
	    Util.abandonEphemeralHashSetIterator(t);
	  }
	  synchronized (description_index) {
	    matched_ids = description_index.get(keyword);
	  }
	  if (matched_ids != null) {
            Util.createEphemeralHashSetIterator(t);
	    synchronized (matched_ids) {
	      for (Long id: matched_ids) {
		addToSetIfAvailable(t, intersection, id);
	      }
	    }
	    Util.abandonEphemeralHashSetIterator(t);
	  }
	} else {
	  ExtrememHashSet<Long> matched_ids;
          ExtrememHashSet<Product> new_matches = new ExtrememHashSet<Product>(t, LifeSpan.Ephemeral);
          synchronized (name_index) {
            matched_ids = name_index.get(keyword);
          }
          if (matched_ids != null) {
	    Util.createEphemeralHashSetIterator(t);
            synchronized (matched_ids) {
              for (Long id: matched_ids) {
                addToSetIfAvailable(t, new_matches, id);
              }
            }
            Util.abandonEphemeralHashSetIterator(t);
          }
          synchronized (description_index) {
            matched_ids = description_index.get(keyword);
          }
          if (matched_ids != null) {
            Util.createEphemeralHashSetIterator(t);
            synchronized (matched_ids) {
              for (Long id: matched_ids) {
                addToSetIfAvailable(t, new_matches, id);
              }
            }
            Util.abandonEphemeralHashSetIterator(t);
          }
          ExtrememHashSet<Product> remove_set = new ExtrememHashSet<Product>(t, LifeSpan.Ephemeral);
          Util.createEphemeralHashSetIterator(t);
          for (Product p: intersection) {
            if (!new_matches.contains(p)) {
              remove_set.add(t, p);
            }
          }
          Util.abandonEphemeralHashSetIterator(t);
          new_matches.garbageFootprint(t);
          Util.createEphemeralHashSetIterator(t);
          for (Product p: remove_set) {
            intersection.remove(t, p);
          }
          Util.abandonEphemeralHashSetIterator(t);
          remove_set.garbageFootprint(t);
          if (intersection.size() == 0) {
            Util.ephemeralReferenceArray(t, 0);
            // Returning an array with no entries.
            return new Product[0];
          }
        }
      }    // end for loop

      Product[] result = new Product[intersection.size()];
      Util.ephemeralReferenceArray(t, result.length);
      int j = 0;
      Util.createEphemeralHashSetIterator(t);
      for (Product p: intersection)
        result[j++] = p;
      Util.abandonEphemeralHashSetIterator(t);
      intersection.garbageFootprint(t);
      return result;
    }
  }

  Product[] lookupProductsMatchingAll(ExtrememThread t, String [] keywords) {
    if (config.PhasedUpdates()) {
      CurrentProductsData all_products_currently = getUpdatedDataBase();
      return lookupProductsMatchingAllPhasedUpdates(t, keywords, all_products_currently);
    } else if (config.FastAndFurious()) {
      return lookupProductsMatchingAllFastAndFurious(t, keywords);
    } else {
      SearchNamesAll sna = new SearchNamesAll(t, keywords, this);
      cc.actAsReader(sna);
      sna.garbageFootprint(t);
      return sna.results;
    }
  }

  Product[] lookupProductsMatchingAnyPhasedUpdates(ExtrememThread t, String [] keywords, CurrentProductsData products) {
    ExtrememHashSet<Product> accumulator = new ExtrememHashSet<Product>(t, LifeSpan.Ephemeral);
    for (int i = 0; i < keywords.length; i++) {
      String keyword = keywords[i];
      ExtrememHashSet<Long> matched_ids = name_index.get(keyword);
      matched_ids = name_index.get(keyword);
      if (matched_ids != null) {
        Util.createEphemeralHashSetIterator(t);
        for (Long id: matched_ids) {
          addToSetIfAvailable(t, accumulator, id);
        }
        Util.abandonEphemeralHashSetIterator(t);
      }
      matched_ids = description_index.get(keyword);
      if (matched_ids != null) {
        Util.createEphemeralHashSetIterator(t);
        for (Long id: matched_ids) {
          addToSetIfAvailable(t, accumulator, id);
        }
        Util.abandonEphemeralHashSetIterator(t);
      }
    }
    Product[] result = new Product[accumulator.size()];
    Util.ephemeralReferenceArray(t, result.length);
    int j = 0;
    Util.createEphemeralHashSetIterator(t);
    for (Product p: accumulator)
      result[j++] = p;
    Util.abandonEphemeralHashSetIterator(t);
    accumulator.garbageFootprint(t);
    return result;
  }

  Product[] lookupProductsMatchingAny(ExtrememThread t, String [] keywords) {
    if (config.PhasedUpdates()) {
      CurrentProductsData all_products_currently = getUpdatedDataBase();
      return lookupProductsMatchingAnyPhasedUpdates(t, keywords, all_products_currently);
    } else if (config.FastAndFurious()) {
      ExtrememHashSet<Product> accumulator = new ExtrememHashSet<Product>(t, LifeSpan.Ephemeral);
      for (int i = 0; i < keywords.length; i++) {
        String keyword = keywords[i];
        ExtrememHashSet<Long> matched_ids = name_index.get(keyword);
        synchronized (name_index) {
          matched_ids = name_index.get(keyword);
        }
        if (matched_ids != null) {
          Util.createEphemeralHashSetIterator(t);
          synchronized (matched_ids) {
            for (Long id: matched_ids) {
              addToSetIfAvailable(t, accumulator, id);
            }
          }
          Util.abandonEphemeralHashSetIterator(t);
        }
        synchronized (description_index) {
          matched_ids = description_index.get(keyword);
        }
        if (matched_ids != null) {
          Util.createEphemeralHashSetIterator(t);
          synchronized (matched_ids) {
            for (Long id: matched_ids) {
              addToSetIfAvailable(t, accumulator, id);
            }
          }
          Util.abandonEphemeralHashSetIterator(t);
        }
      }
      Product[] result = new Product[accumulator.size()];
      Util.ephemeralReferenceArray(t, result.length);
      int j = 0;
      Util.createEphemeralHashSetIterator(t);
      for (Product p: accumulator)
        result[j++] = p;
      Util.abandonEphemeralHashSetIterator(t);
      accumulator.garbageFootprint(t);
      return result;
    } else {
      SearchNamesAny sna = new SearchNamesAny(t, keywords, this);
      cc.actAsReader(sna);
      sna.garbageFootprint(t);
      return sna.results;
    }
  }

  // Rebuild Products data base from change_log.  Return the number of products actually replaced.
  long rebuildProductsPhasedUpdates(ExtrememThread t) {

    String s;
    AbsoluteTime now = AbsoluteTime.now(t);
    if (config.ReportCSV()) {
      s = Long.toString(now.microseconds());
      Util.ephemeralString(t, s.length());
      Report.output("Phase Rebuild of Product Database Start Time, ", s);
    } else {
      s = now.toString(t);
      Util.ephemeralString(t, s.length());
      Report.output("Phase Rebuild of Product Database Start Time: ", s);
    }
    Util.abandonEphemeralString(t, s);
    now.garbageFootprint(t);

    ArrayletOflong new_product_ids;
    TreeMap <Long, Product> new_product_map;
    TreeMap <String, ExtrememHashSet<Long>> new_name_index;
    TreeMap <String, ExtrememHashSet<Long>> new_description_index;
    long tally = 0;

    LifeSpan ls = this.intendedLifeSpan();
    int num_products = config.NumProducts();
    new_product_ids = new ArrayletOflong(t, ls, config.MaxArrayLength(), num_products);
    new_product_map = new TreeMap<Long, Product>();
    new_name_index = new TreeMap<String, ExtrememHashSet<Long>>();
    new_description_index = new TreeMap<String, ExtrememHashSet<Long>>();

    // First, copy the existing data base
    for (int i = 0; i < num_products; i++) {
      long product_id = product_ids.get(i);
      Product product = product_map.get(product_id);
      new_product_ids.set(i, product_id);
      new_product_map.put(product_id, product);
    }

    // Then, modify the data base according to content of the change log.
    ChangeLogNode change;
    while ((change = change_log.pull()) != null) {
      int replacement_index = change.index();
      long replacement_product_id = product_ids.get(replacement_index);
      tally++;

      new_product_map.remove(replacement_product_id);

      Product new_product = change.product();
      long new_product_id = new_product.id();
      new_product_ids.set(replacement_index, new_product_id);
      new_product_map.put(new_product_id, new_product);
    }

    // Now, build the replacement indexes
    for (int i = 0; i < num_products; i++) {
      long product_id = new_product_ids.get(i);
      Product product = new_product_map.get(product_id);
      addToIndicesPhasedUpdates(t, product, new_name_index, new_description_index);
    }
    establishUpdatedDataBase(t, new_product_ids, new_product_map, new_name_index, new_description_index);

    now = AbsoluteTime.now(t);
    if (config.ReportCSV()) {
      s = Long.toString(now.microseconds());
      Util.ephemeralString(t, s.length());
      Report.output("Phase Rebuild of Product Database Finish Time, ", s);
    } else {
      s = now.toString(t);
      Util.ephemeralString(t, s.length());
      Report.output("Phase Rebuild of Product Database Finish Time: ", s);
    }
    Util.abandonEphemeralString(t, s);
    now.garbageFootprint(t);

    return tally;
  }

  // Memory footprint may change as certain product names and
  // descriptions are replaced.
  void tallyMemory (MemoryLog log,  LifeSpan ls, Polarity p) {

    super.tallyMemory (log, ls, p);
    int num_products = config.NumProducts();

    // Don't account for config object.  Its memory is accounted
    // elsewhere.

    // Account for object referenced by cc
    cc.tallyMemory(log, ls, p);

    // Account for 4 int fields: nie, nip, die, dip;
    // 7 long fields: pncl, nicl, pdcl, dicl, npi, nihacl, dihacl.
    log.accumulate(ls, MemoryFlavor.ObjectRSB, p,
                   7 * Util.SizeOfLong + 4 * Util.SizeOfInt);

    // Account for 6 reference fields: product_ids, cc, config,
    // product_map, name_index, description_index.  
    log.accumulate(ls, MemoryFlavor.ObjectReference, p, 6);

    // Account for array referenced by product_ids
    product_ids.tallyMemory(log, ls, p);

    // Account for object referenced by product_map
    Util.tallyTreeMap(num_products, log, ls, p);

    // Each key within product_map is a Long object, with a single long field
    log.accumulate(ls, MemoryFlavor.PlainObject, p, num_products);
    log.accumulate(ls, MemoryFlavor.ObjectRSB, p,
                   num_products * Util.SizeOfLong);

    // Account for the content of product_map.  Each content entry is
    // a Product.  First account for num_products instances of
    // Product, excluding each product's name and description Strings.
    Product.tallyMemory(log, ls, p, num_products);

    Thread current = Thread.currentThread();
    if (current instanceof ExtrememThread) {
      ExtrememThread t = (ExtrememThread) current;
      doVariableTally(t, log, ls, p);
    } else
      Util.internalError(
        "Only ExtrememThread instances may invoke Products.tallyMemory");
  }

  private void doVariableTally(ExtrememThread t, MemoryLog log,
                               LifeSpan ls, Polarity p) {
    if (config.FastAndFurious()) {
      int num_products = config.NumProducts();

      synchronized (this) {
        // Need synchronizat to access pncl, pdcl, nie, nicl, etc.

        // Account for the cumulative memory consumed by Product names.
        Util.tallyStrings(log, ls, p, num_products, pncl);

        // Account for the cumulative memory consumed by Product descriptions.
        Util.tallyStrings(log, ls, p, num_products, pdcl);

        // Account for the name_index.
        Util.tallyTreeMap(nie, log, ls, p);

        // Each key within name_index is a unique word (not stored elsewhere)
        Util.tallyStrings(log, ls, p, nie, nicl);

        // Each content within name_index is an ExtrememHashSet of Long.
        ExtrememHashSet.tallyMemory(log, ls, p, nie, nip, nihacl);

        // Account for the Long instances that are stored within the ExtrememHashSets.
        log.accumulate(ls, MemoryFlavor.PlainObject, p, nip);
        log.accumulate(ls, MemoryFlavor.ObjectRSB, p, nip * Util.SizeOfLong);

        // Account for description_index.
        Util.tallyTreeMap(die, log, ls, p);
        // Each key within description_index is a unique word (not stored elsewhere). 
    
        Util.tallyStrings(log, ls, p, die, dicl);

        // Each content within description_index is an ExtrememHashSet of Long.
        ExtrememHashSet.tallyMemory(log, ls, p, die, dip, dihacl);

        // Account for the Long instances that are stored within the HashSets.
        log.accumulate(ls, MemoryFlavor.PlainObject, p, dip);
        log.accumulate(ls, MemoryFlavor.ObjectRSB, p, dip * Util.SizeOfLong);
      }
    } else {
      BeanCounter bc = new BeanCounter(t, this, log, ls, p);
      cc.actAsReader(bc);
      bc.garbageFootprint(t);
    }
  }

  // Need to hold a reader lock in order to access state variables
  // that are modified by threads holding the writer lock.
  void controlledVariableTally(MemoryLog log, LifeSpan ls, Polarity p) {
    int num_products = config.NumProducts();

    // Account for the cumulative memory consumed by Product names.
    Util.tallyStrings(log, ls, p, num_products, pncl);

    // Account for the cumulative memory consumed by Product descriptions.
    Util.tallyStrings(log, ls, p, num_products, pdcl);

    // Account for the name_index.
    Util.tallyTreeMap(nie, log, ls, p);

    // Each key within name_index is a unique word (not stored elsewhere)
    Util.tallyStrings(log, ls, p, nie, nicl);

    // Each content within name_index is an ExtrememHashSet of Long.
    ExtrememHashSet.tallyMemory(log, ls, p, nie, nip, nihacl);

    // Account for the Long instances that are stored within the
    // ExtrememHashSets.
    log.accumulate(ls, MemoryFlavor.PlainObject, p, nip);
    log.accumulate(ls, MemoryFlavor.ObjectRSB, p, nip * Util.SizeOfLong);

    // Account for description_index.
    Util.tallyTreeMap(die, log, ls, p);
    // Each key within description_index is a unique word (not stored
    // elsewhere). 
    
    Util.tallyStrings(log, ls, p, die, dicl);

    // Each content within description_index is an ExtrememHashSet of Long.
    ExtrememHashSet.tallyMemory(log, ls, p, die, dip, dihacl);

    // Account for the Long instances that are stored within the HashSets.
    log.accumulate(ls, MemoryFlavor.PlainObject, p, dip);
    log.accumulate(ls, MemoryFlavor.ObjectRSB, p, dip * Util.SizeOfLong);
  }

  /*
   * PRIVATE METHODS
   */

  // The accumulator argument is presumed to be thread-local, for the
  // purposes of preparing a response to a customer inquiry.
  private void addToSetIfAvailable(ExtrememThread t,
                                   ExtrememHashSet<Product> accumulator,
                                   Long id) {
    Product p = product_map.get(id);
    if ((p != null) && p.available())
      accumulator.add(t, p);
  }

  // Return an ephemeral array representing Products that match any of
  // keywords.
  private Product [] controlledSearchAny (String [] keywords,
                                          ExtrememThread t) {
    int i;
    ExtrememHashSet<Product> accumulator = (
      new ExtrememHashSet<Product>(t, LifeSpan.Ephemeral));
    for (i = 0; i < keywords.length; i++) {
      String keyword = keywords[i];
      ExtrememHashSet<Long> matched_ids = name_index.get(keyword);
      if (matched_ids != null) {
        Util.createEphemeralHashSetIterator(t);
        for (Long id: matched_ids)
          addToSetIfAvailable(t, accumulator, id);
        Util.abandonEphemeralHashSetIterator(t);
      }
      matched_ids = description_index.get(keyword);
      if (matched_ids != null) {
        Util.createEphemeralHashSetIterator(t);
        for (Long id: matched_ids)
          addToSetIfAvailable(t, accumulator, id);
        Util.abandonEphemeralHashSetIterator(t);
      }
    }
    int result_length = accumulator.size();
    Product[] result = new Product[result_length];
    Util.ephemeralReferenceArray(t, result_length);
    i = 0;

    Util.createEphemeralHashSetIterator(t);
    for (Product p: accumulator)
      result[i++] = p;
    Util.abandonEphemeralHashSetIterator(t);

    // accumulator is now garbage
    accumulator.garbageFootprint(t);
    return result;
  }

  private Product[] controlledSearchAll(String[] keywords,
                                        ExtrememThread t) {
    ExtrememHashSet<Product> intersection = (
      new ExtrememHashSet<Product>(t, LifeSpan.Ephemeral));
    for (int i = 0; i < keywords.length; i++) {
      if (i == 0) {
        String keyword = keywords[i];
        ExtrememHashSet<Long> matched_ids = name_index.get(keyword);
        if (matched_ids != null) {
          Util.createEphemeralHashSetIterator(t);
          for (Long id: matched_ids)
            addToSetIfAvailable(t, intersection, id);
          Util.abandonEphemeralHashSetIterator(t);
        }
        matched_ids = description_index.get(keyword);
        if (matched_ids != null) {
          Util.createEphemeralHashSetIterator(t);
          for (Long id: matched_ids)
            addToSetIfAvailable(t, intersection, id);
          Util.abandonEphemeralHashSetIterator(t);
        }
      } else {
        ExtrememHashSet<Product> new_matches = (
          new ExtrememHashSet<Product>(t, LifeSpan.Ephemeral));
        String keyword = keywords[i];
        ExtrememHashSet<Long> matched_ids = name_index.get(keyword);
        if (matched_ids != null) {
          Util.createEphemeralHashSetIterator(t);
          for (Long id: matched_ids)
            addToSetIfAvailable(t, new_matches, id);
          Util.abandonEphemeralHashSetIterator(t);
        }
        matched_ids = description_index.get(keyword);
        if (matched_ids != null) {
          Util.createEphemeralHashSetIterator(t);
          for (Long id: matched_ids)
            addToSetIfAvailable(t, new_matches, id);
          Util.abandonEphemeralHashSetIterator(t);
        }
        ExtrememHashSet<Product> remove_set = (
          new ExtrememHashSet<Product>(t, LifeSpan.Ephemeral));
        Util.createEphemeralHashSetIterator(t);
        for (Product p: intersection) {
          if (!new_matches.contains(p))
            remove_set.add(t, p);
        }
        Util.abandonEphemeralHashSetIterator(t);
        new_matches.garbageFootprint(t);

        Util.createEphemeralHashSetIterator(t);
        for (Product p: remove_set)
          intersection.remove(t, p);
        Util.abandonEphemeralHashSetIterator(t);
        remove_set.garbageFootprint(t);
      }
      if (intersection.size() == 0) {
        Util.ephemeralReferenceArray(t, 0);
        return new Product[0];
      }
    }
    Product[] result = new Product[intersection.size()];
    Util.ephemeralReferenceArray(t, result.length);
    int j = 0;
    Util.createEphemeralHashSetIterator(t);
    for (Product p: intersection)
      result[j++] = p;
    Util.abandonEphemeralHashSetIterator(t);
    intersection.garbageFootprint(t);
    return result;
  }

  private synchronized long nextUniqId() {
    return npi++;
  }

  // Returns String presumed to reside in Ephemeral memory
  private String randomName(ExtrememThread t) {
    return Util.randomString(t, config.ProductNameLength(), config);
  }

  // Returns String presumed to reside in Ephemeral memory
  private String randomDescription(ExtrememThread t) {
    return Util.randomString(t, config.ProductDescriptionLength(), config);
  }

  // Starting from specified index position, returns the first
  // non-space character or length of string, whichever comes first.
  private int skipSpaces(String s, int index) {
    while ((index < s.length()) && (s.charAt(index) == ' '))
      index++;
    return index;
  }

  // Starting from specified index position, returns index of first
  // space character or length of string, whichever comes first.
  private final int skipNonSpaces (String s, int index) {
    while ((index < s.length()) && (s.charAt(index) != ' '))
      index++;
    return index;
  }

  // Precondition: thread holds a write exclusion lock.  Incoming argument
  // word is privately allocated and is presumed to reside in
  // ephemeral memory.
  //
  // Return the set at the specified index position, creating the set
  // with the same LifeSpan as this.  If a new set is created, convert
  // the word argument to have the same LifeSpan as this object.
  // Otherwise, treat the word argument as garbage.
  private ExtrememHashSet<Long> getSetAtIndex(
    ExtrememThread t, boolean is_name_index, String word,
    TreeMap <String, ExtrememHashSet<Long>> index) {
    int word_length = word.length ();
    LifeSpan ls = this.intendedLifeSpan();
    
    ExtrememHashSet<Long> set = index.get(word);
    if (set == null) {
      set = new ExtrememHashSet<Long>(t, ls);
      index.put(word, set);
      // Indicate that word will persist as part of this object's lifespan.
      Util.convertEphemeralString(t, ls, word_length);

      if (is_name_index) {
        nie++;
        nicl += word.length();
        nihacl += set.capacity();
      } else {
        die++;
        dicl += word.length();
        dihacl += set.capacity();
      }
    } else {
      // Indicate that word has become garbage.
      Util.abandonEphemeralString(t, word_length);
    }
    return set;
  }

  // Thread does not hold exclusion lock and does not require it.  Memory accounting is not fully implemented.
  private void addStringToIndexPhasedUpdates(ExtrememThread t, long id, boolean is_name_index, String s,
                                             TreeMap <String, ExtrememHashSet<Long>> index) {
    LifeSpan ls = this.intendedLifeSpan();
    // Assume first characters of s not equal to space
    for (int start = 0; start < s.length(); start = skipSpaces(s, start)) {
      int end = skipNonSpaces(s, start);
      String word = s.substring(start, end);
      int word_length = end - start;
      start = end;

      ExtrememHashSet<Long> set;
      set = index.get(word);
      if (set == null) {
        // Do the allocation of new HashSet outside of synchronized context
        set = new ExtrememHashSet<Long>(t, ls);
        if (index.get(word) == null) {
          index.put(word, set);
        }
      }
      // id gets auto-boxed to Long
      long orig_capacity = set.capacity();
      boolean success;
      long new_capacity;
      // Note: there may be allocation within this synchronized block to represent id
      success = set.add(t, id);
    }
  }



  // Thread does not hold exclusion lock.
  // This method accounts for memory required to autobox id, and to
  // create or expand the ExtrememHashSet<Long>, as appropriate.
  private void
  addStringToIndexFastAndFurious(ExtrememThread t, long id,
                                 boolean is_name_index, String s,
                                 TreeMap <String, ExtrememHashSet<Long>> index) {
    MemoryLog log = t.memoryLog();
    LifeSpan ls = this.intendedLifeSpan();
    Polarity Grow = Polarity.Expand;
    // Assume first characters of s not equal to space
    for (int start = 0; start < s.length(); start = skipSpaces(s, start)) {
      int end = skipNonSpaces(s, start);
      String word = s.substring(start, end);
      int word_length = end - start;
      Util.ephemeralString(t, word_length);
      start = end;

      ExtrememHashSet<Long> set;
      synchronized (index) {
        set = index.get(word);
      }
      if (set == null) {
        // Do the allocation of new HashSet outside of synchronized context
        set = new ExtrememHashSet<Long>(t, ls);
        synchronized (index) {
          if (index.get(word) == null) {
            index.put(word, set);
          }
        }
        // Indicate that word will persist as part of this object's lifespan.
        Util.convertEphemeralString(t, ls, word_length);

        if (is_name_index) {
          synchronized (this) {
            nie++;
            nicl += word.length();
            nihacl += set.capacity();
          }
        } else {
          synchronized (this) {
            die++;
            dicl += word.length();
            dihacl += set.capacity();
          }
        }
      } else {
        // Replica of word is already present and accounted for.  Abandon our local copy.
        Util.abandonEphemeralString(t, word_length);
      }
      // id gets auto-boxed to Long
      long orig_capacity = set.capacity();
      boolean success;
      long new_capacity;
      synchronized (set) {
        // Note: there may be allocation within this synchronized block to represent id
        success = set.add(t, id);
        new_capacity = set.capacity();
      }
      if (success) {
        // Account for autoboxing of id to Long
        Util.nonEphemeralLong(t, ls);
        if (is_name_index) {
          synchronized (this) {
            nihacl += new_capacity - orig_capacity;
            nip++;
          }
        } else {
          synchronized (this) {
            dip++;
            dihacl += new_capacity - orig_capacity;
          }
        }
      } else {
        // The autoboxed id is Ephemeral, then becomes garbage.
        Util.ephemeralLong(t);
        Util.abandonEphemeralLong(t);
      }
    }
  }

  // Precondition: thread holds the exclusion lock.
  // This method accounts for memory required to autobox id, and to
  // create or expand the ExtrememHashSet<Long>, as appropriate.
  private void
  addStringToIndex(ExtrememThread t, long id,
                   boolean is_name_index, String s,
                   TreeMap <String, ExtrememHashSet<Long>> index) {
    MemoryLog log = t.memoryLog();
    LifeSpan ls = this.intendedLifeSpan();
    Polarity Grow = Polarity.Expand;
    // Assume first characters of s not equal to space
    for (int start = 0; start < s.length(); start = skipSpaces(s, start)) {
      int end = skipNonSpaces(s, start);
      String word = s.substring(start, end);
      int word_length = end - start;
      Util.ephemeralString(t, word_length);
      start = end;

      // getSetAtIndex accounts for word's memory.
      ExtrememHashSet<Long> set = getSetAtIndex(t, is_name_index, word, index);


      // id gets auto-boxed to Long
      long orig_capacity = set.capacity();
      if (set.add(t, id)) {
        // Account for autoboxing of id to Long
        Util.nonEphemeralLong(t, ls);
        if (is_name_index) {
          nihacl += set.capacity() - orig_capacity;
          nip++;
        } else {
          dip++;
          dihacl += set.capacity() - orig_capacity;
        }
      } else {
        // The autoboxed id is Ephemeral, then becomes garbage.
        Util.ephemeralLong(t);
        Util.abandonEphemeralLong(t);
      }
    }
  }

  private void addToIndicesPhasedUpdates(ExtrememThread t, Product p,
                                         TreeMap<String, ExtrememHashSet<Long>> name_map,
                                         TreeMap<String, ExtrememHashSet<Long>> desc_map) {
    long id = p.id ();

    // Memory accounting not implemented for PhasedUpdates
    addStringToIndexPhasedUpdates(t, id, true, p.name(), name_map);
    addStringToIndexPhasedUpdates(t, id, false, p.description(), desc_map);
  }

  // Thread does not hold exclusion lock.
  private void addToIndicesFastAndFurious(ExtrememThread t, Product p) {
    long id = p.id ();

    // Memory accounting not implemented for FastAndFurious
    addStringToIndexFastAndFurious(t, id, true, p.name(), name_index);
    addStringToIndexFastAndFurious(t, id, false, p.description(), description_index);
  }

  // Precondition: thread holds the exclusion lock
  private void addToIndices(ExtrememThread t, Product p) {
    long id = p.id ();
    MemoryLog log = t.memoryLog();

    addStringToIndex(t, id, true, p.name(), name_index);
    addStringToIndex(t, id, false, p.description(), description_index);
  }

  // Thread does not hold exclusion lock
  private void rmStringFromIndexFastAndFurious(ExtrememThread t, long id,
                                               boolean is_name_index, String s,
                                               TreeMap <String, ExtrememHashSet<Long>> index) {
    LifeSpan ls = this.intendedLifeSpan();
    // Assume first characters of s not equal to space
    for (int start = 0; start < s.length (); start = skipSpaces(s, start)) {
      int end = skipNonSpaces(s, start);
      String word = s.substring(start, end);
      int word_length = end - start;
      Util.ephemeralString(t, word_length);
      start = end;
      ExtrememHashSet<Long> set;
      synchronized (index) {
        set = index.get(word);
      }
      Util.abandonEphemeralString(t, word_length);
      if (set != null) {
        Polarity Grow = Polarity.Expand;
        MemoryLog garbage = t.garbageLog();
        // Even if the set size is decreased to zero, we do not destroy
        // the set, nor do we reclaim the String that keys to this set.
        // id gets autoboxed to Long and immediately abandoned.
        Util.ephemeralLong(t);
        Util.abandonEphemeralLong(t);
        boolean success;
        synchronized (set) {
          success = set.remove(t, id);
        }
        if (success) {
          // Abandon the value removed from the set
          Util.abandonNonEphemeralLong(t, ls);
          if (is_name_index) {
            synchronized (this) {
              nip--;
            }
          } else {
            synchronized (this) {
              dip--;
            }
          }
        }
      }  // else, shouldn't happen.
    }
  }

  // Precondition: thread holds the exclusion lock
  private void rmStringFromIndex(ExtrememThread t, long id,
                                 boolean is_name_index, String s,
                                 TreeMap <String, ExtrememHashSet<Long>> index) {
    LifeSpan ls = this.intendedLifeSpan();
    // Assume first characters of s not equal to space
    for (int start = 0; start < s.length (); start = skipSpaces(s, start)) {
      int end = skipNonSpaces(s, start);
      String word = s.substring(start, end);
      int word_length = end - start;
      Util.ephemeralString(t, word_length);
      start = end;
      ExtrememHashSet<Long> set = index.get(word);
      Util.abandonEphemeralString(t, word_length);
      if (set != null) {
        Polarity Grow = Polarity.Expand;
        MemoryLog garbage = t.garbageLog();
        // Even if the set size is decreased to zero, we do not destroy
        // the set, nor do we reclaim the String that keys to this set.
        // id gets autoboxed to Long and immediately abandoned.
        Util.ephemeralLong(t);
        Util.abandonEphemeralLong(t);
        if (set.remove(t, id)) {
          // Abandon the value removed from the set
          Util.abandonNonEphemeralLong(t, ls);
          if (is_name_index)
            nip--;
          else
            dip--;
        }
      }  // else, shouldn't happen.
    }
  }

  // Thread does not hold an exclusion lock.  So we must synchronize locally for each change made.
  private void rmFromIndicesFastAndFurious(ExtrememThread t, Product p) {
    long id = p.id();

    rmStringFromIndexFastAndFurious(t, id, true, p.name(), name_index);
    rmStringFromIndexFastAndFurious(t, id, false, p.description(), description_index);
  }

  // Precondition: thread holds the exclusion lock
  private void rmFromIndices(ExtrememThread t, Product p) {
    long id = p.id();

    rmStringFromIndex(t, id, true, p.name(), name_index);
    rmStringFromIndex(t, id, false, p.description(), description_index);
  }

  void controlledReport(ExtrememThread t, boolean reportCSV) {
    Report.output("Products concurrency report:");
    cc.report(t, reportCSV);
  }

  void report(ExtrememThread t) {
    if (config.FastAndFurious()) {
      Report.output("No Products concurrency report since configuration is FastAndFurious");
    } else {
      Reporter r = new Reporter(t, this, config.ReportCSV());
      cc.actAsReader(r);
      r.garbageFootprint(t);
    }
  }

  /*
   * INNER CLASSES
   */

  // Inner class used in implementation of lookupProductsMatchingAll ()
  private static class SearchNamesAll extends ExtrememObject implements Actor {
    ExtrememThread t;
    Products products;
    String[] keywords;
    Product[] results;

    // Assume instance is Ephemeral
    SearchNamesAll(ExtrememThread t, String [] keywords, Products products) {
      super(t,LifeSpan.Ephemeral);

      this.products = products;
      this.keywords = keywords;
      this.t = t;
    }

    public void act () {
      results = products.controlledSearchAll(keywords, t);
    }

    void tallyMemory(MemoryLog log, LifeSpan ls, Polarity p) {
      super.tallyMemory(log, ls, p);
      // Account for t, products, keywords, results
      log.accumulate(ls, MemoryFlavor.ObjectReference, p, 4);
    }
  }

  private static class BeanCounter extends ExtrememObject implements Actor {
    ExtrememThread thread;
    Products products;
    MemoryLog log;
    LifeSpan ls;
    Polarity polarity;

    // Argument t is the currently running thread.  This thread takes
    // responsibility for the allocations that are performed as part
    // of "counting the beans".  Normally, log is also associated with
    // the currently running thread, but not necessarily.
    BeanCounter(ExtrememThread t, Products p,
                MemoryLog log, LifeSpan ls, Polarity polarity) {
      super(t, LifeSpan.Ephemeral);
      MemoryLog thread_log = t.memoryLog();
      // Account for thread, products, log, ls, polarity
      thread_log.accumulate(LifeSpan.Ephemeral, MemoryFlavor.ObjectReference,
                     Polarity.Expand, 5);
      this.thread = t;
      this.products = p;
      this.log = log;
      this.ls = ls;
      this.polarity = polarity;
    }

    public void act() {
      products.controlledVariableTally(log, ls, polarity);
    }

    void tallyMemory(MemoryLog log, LifeSpan ls, Polarity p) {
      super.tallyMemory(log, ls, p);
      // Account for thread, products, log, ls, polarity
      log.accumulate(LifeSpan.Ephemeral, MemoryFlavor.ObjectReference, p, 5);
    }
  }

  // Inner class used in implementation of lookupProductsMatchingAny ()
  private static class SearchNamesAny extends ExtrememObject implements Actor {
    ExtrememThread t;
    Products products;
    String[] keywords;
    Product[] results;
    
    SearchNamesAny(ExtrememThread t, String[] keywords, Products products) {
      super(t, LifeSpan.Ephemeral);
      MemoryLog log = t.memoryLog();
      // Account for t, products, keywords, results
      log.accumulate(LifeSpan.Ephemeral, MemoryFlavor.ObjectReference,
                     Polarity.Expand, 4);
      this.keywords = keywords;
      this.t = t;
      this.products = products;
    }

    public void act() {
      results = products.controlledSearchAny(keywords, t);
    }

    void tallyMemory(MemoryLog log, LifeSpan ls, Polarity p) {
      super.tallyMemory(log, ls, p);
      // Account for t, products, keywords, results
      log.accumulate(ls, MemoryFlavor.ObjectReference, p, 4);
    }
  }

  // Inner class used in implementation of report ()
  private static class Reporter extends ExtrememObject implements Actor {
    ExtrememThread t;
    Products products;
    boolean reportCSV;

    Reporter(ExtrememThread t, Products p, boolean reportCSV) {
      super(t, LifeSpan.Ephemeral);
      MemoryLog log = t.memoryLog();
      // Account for t, products
      log.accumulate(LifeSpan.Ephemeral, MemoryFlavor.ObjectReference,
                     Polarity.Expand, 2);
      this.t = t;
      this.products = p;
      this.reportCSV = reportCSV;
    }

    public void act() {
      products.controlledReport(t, reportCSV);
    }

    void tallyMemory(MemoryLog log, LifeSpan ls, Polarity p) {
      super.tallyMemory(log, ls, p);
      // Account for t, products
      log.accumulate(ls, MemoryFlavor.ObjectReference, p, 2);
      // Account for reportCSV
      log.accumulate(ls, MemoryFlavor.ObjectRSB, p, Util.SizeOfBoolean);
    }
  }

  // Inner class used in implementation of fetchProductByIndex ()
  private static class ProductSelector
    extends ExtrememObject implements Actor {
    ExtrememThread t;
    Products products;
    Product result;
    int index;
    
    ProductSelector(ExtrememThread t, int index, Products p) {
      super(t, LifeSpan.Ephemeral);
      MemoryLog log = t.memoryLog();
      // Account for t, products, result
      log.accumulate(LifeSpan.Ephemeral, MemoryFlavor.ObjectReference,
                     Polarity.Expand, 3);
      // Accoun for index
      log.accumulate(LifeSpan.Ephemeral, MemoryFlavor.ObjectRSB,
                     Polarity.Expand, Util.SizeOfInt);
      this.index = index;
      this.t = t;
      this.products = p;
    }

    public void act() {
      result = products.controlledFetchProductByIndex(t, index);
    }

    void tallyMemory(MemoryLog log, LifeSpan ls, Polarity p) {
      super.tallyMemory(log, ls, p);
      // Account for t, products, result
      log.accumulate(ls, MemoryFlavor.ObjectReference, p, 3);
      // Account for index
      log.accumulate(ls, MemoryFlavor.ObjectRSB, p, Util.SizeOfInt);
    }
  }

  // Inner class used in implementation of replaceArbitraryProduct ()
  private static class ProductReplacer
    extends ExtrememObject implements Actor {
    ExtrememThread t;
    Products all_products;
    Product product;
    Product removed_product;

    ProductReplacer(ExtrememThread t, Products all_products, Product product) {
      super(t, LifeSpan.Ephemeral);
      // Account for t, all_products, product, removed_product
      MemoryLog log = t.memoryLog();
      log.accumulate(LifeSpan.Ephemeral, MemoryFlavor.ObjectReference,
                     Polarity.Expand, 4);
      this.t = t;
      this.all_products = all_products;
      this.product = product;
    }

    public void act() {
      removed_product =
      all_products.controlledReplaceArbitraryProduct(t, product);
    }

    void tallyMemory(MemoryLog log, LifeSpan ls, Polarity p) {
      super.tallyMemory(log, ls, p);
      // Account for t, all_products, product, removed_product,
      log.accumulate(ls, MemoryFlavor.ObjectReference, p, 4);
    }
  }
}

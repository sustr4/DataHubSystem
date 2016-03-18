/*
 * Data Hub Service (DHuS) - For Space data distribution.
 * Copyright (C) 2013,2014,2015 GAEL Systems
 *
 * This file is part of DHuS software sources.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package fr.gael.dhus.service;

import fr.gael.dhus.database.dao.ActionRecordWritterDao;
import fr.gael.dhus.database.object.Collection;
import fr.gael.dhus.database.object.MetadataIndex;
import fr.gael.dhus.database.object.Product;
import fr.gael.dhus.database.object.User;
import fr.gael.dhus.search.DHusSearchException;
import fr.gael.dhus.search.SolrDao;
import fr.gael.dhus.service.metadata.MetadataType;
import fr.gael.dhus.service.metadata.SolrField;

import java.io.IOException;
import java.util.AbstractList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import org.apache.log4j.Logger;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.Suggestion;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class SearchService extends WebService
{
   /** Logger. */
   private static final Logger LOGGER = Logger.getLogger(SearchService.class);

   /** Autowired dependency. */
   @Autowired
   private SolrDao solrDao;

   /** Autowired dependency. */
   @Autowired
   private SecurityService securityService;

   /** Autowired dependency. */
   @Autowired
   private ActionRecordWritterDao actionRecordWritterDao;

   /** Autowired dependency. */
   @Autowired
   private CollectionService collectionService;

   /** Autowired dependency. */
   @Autowired
   private ProductService productService;

   /** Autowired dependency. */
   @Autowired
   private MetadataTypeService metadataTypeService;


   /**
    * Indexes or Reindexes a product.
    * {@link Product#getId()} is the unique key in the index.
    * @param product a product.
    */
   public void index(Product product)
   {
      try
      {
         long start = System.currentTimeMillis();

         String path = product.getPath().toString();
         if (path.startsWith("/")) // FIXME: should be done by the ingestion process!
         {
            path="file:/" + path;
         }
         LOGGER.debug("Indexing product '" + path + "'");

         SolrInputDocument doc = new SolrInputDocument();
         doc.setField("path", path);
         doc.setField("id", product.getId());
         doc.setField("uuid", product.getUuid());

         // Metadatas
         List<MetadataIndex> indices = product.getIndexes();
         if (indices != null && !indices.isEmpty())
         {
            for (MetadataIndex index : indices)
            {
               String type = index.getType();

               // Only textual information stored in field contents (full-text search)
               if ((type == null) || type.isEmpty() || "text/plain".equals(type))
               {
                  doc.addField("contents", index.getValue());
               }

               // next line is considered bad practice:
               //doc.addField("contents", index.getQueryable());

               MetadataType mt = metadataTypeService
                     .getMetadataTypeByName(product.getItemClass(), index.getName());
               SolrField sf = (mt != null)? mt.getSolrField(): null;

               if (sf != null || index.getQueryable() != null)
               {
                  Boolean is_multivalued = (sf != null)? sf.isMultiValued(): null;
                  String field_name = (sf != null)? sf.getName(): index.getQueryable().toLowerCase();

                  if (is_multivalued != null && is_multivalued)
                  {
                     doc.addField(field_name, index.getValue());
                  }
                  else
                  {
                     doc.setField(field_name, index.getValue());
                  }

                  LOGGER.debug("Added " + field_name + ":" + index.getValue());
               }
            }
         }
         else
         {
            LOGGER.warn("Product '" + product.getIdentifier() + "' contains no metadata");
         }

         // Collections
         List<Collection> collections = collectionService.getCollectionsOfProduct(product);
         if (collections != null && !collections.isEmpty())
         {
            for (Collection collection : collections)
            {
               doc.addField("collection", collection.getName());
            }
         }

         try
         {
            solrDao.index(doc);
         }
         catch (IOException | SolrServerException e)
         {
            LOGGER.error("Cannot index product", e);
            return;
         }

         long end = System.currentTimeMillis();
         LOGGER.info("Indexed product in  " + (end - start) + "ms");
      }
      catch (Exception e)
      {
         LOGGER.error("Cannot index product", e);
      }
   }

   /**
    * Removes the given product from the index.
    * @param product to remove.
    */
   public void remove(Product product)
   {
      try
      {
         solrDao.remove(product.getId());
      }
      catch (IOException|SolrServerException ex)
      {
         LOGGER.error("Cannot remove product " + product.getIdentifier() + "from index", ex);
      }
   }

   /**
    * Updates the given product from the index.
    * @param product to update.
    */
   public void update(Product product)
   {
      index(product);
   }

   /**
    * Paginated search for system operations.
    * @param query Solr query `q` parameter.
    * @return an iterator of found products.
    */
   public Iterator<Product> search(String query)
   {
      try
      {
         final Iterator<SolrDocument> it = solrDao.scroll(new SolrQuery(query));

         return new Iterator<Product>()
         {
            @Override
            public boolean hasNext()
            {
               return it.hasNext();
            }

            @Override
            public Product next()
            {
               return productService.getProduct((Long) it.next().get("id"));
            }

            @Override
            public void remove()
            {
               productService.deleteProduct((Long) it.next().get("id"));
            }
         };
      }
      catch (IOException|SolrServerException ex)
      {
         LOGGER.error("An exception occured while searching", ex);
      }
      return Collections.EMPTY_LIST.iterator();
   }

   /**
    * Search.
    * <p>
    * Set `start` and `rows` values in the SolrQuery parameter to paginate the results.<br>
    * <strong>If no `rows` have been set, solr will only return 10 documents, no more.</strong>
    * <p>
    * To get the total number of document matching the given query, use {@code res.getNumFound()}.
    *
    * @param query a SolrQuery with at least a 'q' parameter set.
    * @return A list of solr document matching the given query.
    */
   @PreAuthorize("hasRole('ROLE_SEARCH')")
   public SolrDocumentList search(SolrQuery query)
   {
      Objects.requireNonNull(query);

      User u = securityService.getCurrentUser();
      actionRecordWritterDao.search(query.getQuery(), query.getStart(), query.getRows(), u);

      query.setQuery(solrDao.updateQuery(query.getQuery()));

      try
      {
         return solrDao.search(query).getResults();
      }
      catch (SolrServerException | IOException ex)
      {
         LOGGER.error(ex);
         throw new DHusSearchException("An exception occured while searching", ex);
      }
   }

   /**
    * Returns the product associated with the given solr document.
    * @param doc Index entry for a product, are returned by {@link #search(SolrQuery)}.
    * @return A product (database object).
    */
   public Product asProduct(SolrDocument doc)
   {
      Long pid = Long.class.cast(doc.get("id"));
      return productService.getProduct(pid);
   }

   /**
    * Paginated search.
    * @param query Solr query `q` parameter.
    * @param start_index offset.
    * @param num_element length.
    * @return a list of found products.
    * @deprecated use {@link #search(SolrQuery)}.
    */
   @Deprecated
   @PreAuthorize("hasRole('ROLE_SEARCH')")
   public List<Product> search(String query, int start_index, int num_element)
   {
      if (start_index < 0 || num_element <= 0)
      {
         throw new IllegalArgumentException();
      }

      User u = securityService.getCurrentUser();
      actionRecordWritterDao.search(query, start_index, num_element, u);

      query = solrDao.updateQuery(query);

      SolrQuery squery = new SolrQuery(query);
      squery.setStart(start_index);
      squery.setRows(num_element);

      try
      {
         final QueryResponse qr = solrDao.search(squery);
         return new AbstractList<Product>()
         {
            @Override
            public Product get(int index)
            {
               Long pid = (Long) qr.getResults().get(index).get("id");
               return productService.getProduct(pid);
            }

            @Override
            public int size()
            {
               return qr.getResults().size();
            }
         };
      }
      catch (SolrServerException | IOException ex)
      {
         LOGGER.error(ex);
         throw new DHusSearchException("An exception occured while searching", ex);
      }
   }

   /**
    * Returns how many solr documents match the given query.
    * @param query a solr `q` query.
    * @return solr document count.
    * @deprecated use {@link #search(SolrQuery)}{@code .getNumFound()}.
    */
   @Deprecated
   @PreAuthorize("hasRole('ROLE_SEARCH')")
   public int getResultCount(String query)
   {
      try
      {
         query = solrDao.updateQuery(query);
         return (int) solrDao.search(new SolrQuery(query)).getResults().getNumFound();
      }
      catch (SolrServerException | IOException ex)
      {
         LOGGER.error(ex);
         throw new DHusSearchException("An exception occured while searching", ex);
      }
   }

   /**
    * Returns a list of suggestions for the given input.
    * @param input search input.
    * @return list of suggestions.
    */
   @PreAuthorize("hasRole('ROLE_SEARCH')")
   public List<String> getSuggestions(String input)
   {
      try
      {
         final List<Suggestion> sggs =
               solrDao.getSuggestions(input).getSuggestions().get("suggest");
         return new AbstractList<String>()
         {
            @Override
            public String get(int index)
            {
               return sggs.get(index).getTerm();
            }

            @Override
            public int size()
            {
               return sggs.size();
            }
         };
      }
      catch (IOException|SolrServerException ex)
      {
         LOGGER.error("Cannot get suggestions from Solr", ex);
      }
      return Collections.emptyList();
   }

   /**
    * Integrity check.
    */
   public void checkIndex()
   {
      try
      {
         SolrQuery query = new SolrQuery("*:*");
         query.setFilterQueries("*");
         query.setStart(0);
         Iterator<SolrDocument> it = solrDao.scroll(query);
         while (it.hasNext())
         {
            SolrDocument doc = it.next();
            Long pid = (Long) doc.get("id");
            Product product = productService.systemGetProduct(pid);
            if (product == null)
            {
               Long id = (Long) doc.getFieldValue("id");
               LOGGER.warn("Removing unknown product " + id + " from solr index");
               try
               {
                  solrDao.remove(id);
                  // decrease the offset, because a product has been removed
                  query.setStart(query.getStart() - 1);
               }
               catch (IOException e)
               {
                  LOGGER.error("Cannot remove Solr entry " + id, e);
               }
            }
         }
      }
      catch (IOException|SolrServerException ex)
      {
         LOGGER.error("Cannot check the index", ex);
      }
   }

   /**
    * Optimize the index, merges every segment of the index into one monolithic file.
    * Optimizing is very expensive, and if the index is constantly changing,
    * the slight performance boost will not last long...
    * The tradeoff is not often worth it for a non static index.
    * <p>
    * Blocking method, will block until optimization is complete. Solr won't respond to
    * search queries until optimization is done.
    */
   public void optimizeIndex()
   {
      try
      {
         solrDao.optimize();
      }
      catch (IOException|SolrServerException ex)
      {
         LOGGER.error("Cannot optimize index", ex);
      }
   }
}
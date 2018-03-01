/*
 *  Generika Android
 *  Copyright (C) 2018 ywesee GmbH
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.oddb.generika;

import android.content.Context;
import android.content.Intent;
import android.content.DialogInterface;
import android.os.Bundle;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.NavigationView;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Toast;

import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.vision.barcode.Barcode;

import io.realm.Realm;
import io.realm.RealmChangeListener;
import io.realm.RealmList;
import io.realm.OrderedRealmCollectionChangeListener;
import io.realm.OrderedCollectionChangeSet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

import org.oddb.generika.app.BaseActivity;
import org.oddb.generika.model.Product;
import org.oddb.generika.model.ProductItem;
import org.oddb.generika.network.ProductItemDataFetchFragment;
import org.oddb.generika.ui.list.ProductItemListAdapter;
import org.oddb.generika.util.Constant;


public class MainActivity extends BaseActivity implements
  AdapterView.OnItemClickListener,
  ProductItemListAdapter.DeleteListener,
  ProductItemDataFetchFragment.FetchCallback<
    ProductItemDataFetchFragment.FetchResult> {
  private static final String TAG = "Main";

  // view
  private DrawerLayout drawerLayout;
  private ActionBarDrawerToggle drawerToggle;
  private CharSequence title;
  private ListView listView;

  // database
  private Realm realm;
  private Product product;  // container object
  private ProductItemListAdapter productItemListAdapter;

  // network (headless fragment)
  private boolean fetching = false;
  private ProductItemDataFetchFragment productItemDataFetcher;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    // default: `scanned` product items
    this.realm = Realm.getDefaultInstance();
    this.product = realm.where(Product.class)
      .equalTo("sourceType", "scanned").findFirst();

    FragmentManager fragmentManager = getSupportFragmentManager();
    Fragment fragment = fragmentManager.findFragmentByTag(
      ProductItemDataFetchFragment.TAG);
    if (fragment == null) {
      // TODO: use constant utility
      fragment = ProductItemDataFetchFragment.getInstance(
        fragmentManager, Constant.API_URL_BASE);
    }
    this.productItemDataFetcher = (ProductItemDataFetchFragment)fragment;

    initProductItems();
    initViews();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();

    realm.close();
  }

  private void initProductItems() {
    if (product == null) {
      return;
    }
    if (product.getItems().size() == 0) {
      realm.beginTransaction();
      // placeholder
      ProductItem.Barcode barcode = new ProductItem.Barcode();
      barcode.setValue(Constant.initData.get("ean"));
      ProductItem item = ProductItem.createFromBarcodeIntoSource(
        realm, barcode, product);
      item.setName(Constant.initData.get("name"));
      item.setSize(Constant.initData.get("size"));
      item.setDatetime(Constant.initData.get("datetime"));
      item.setPrice(Constant.initData.get("price"));
      item.setDeduction(Constant.initData.get("deduction"));
      item.setCategory(Constant.initData.get("category"));
      realm.commitTransaction();
    }

    // Check new product item insertion via barcode reader
    RealmList productItems = product.getItems();
    productItems.removeAllChangeListeners();
    productItems.addChangeListener(
      new OrderedRealmCollectionChangeListener<RealmList<ProductItem>>() {

      @Override
      public void onChange(
        RealmList<ProductItem> items,
        OrderedCollectionChangeSet changeSet) {
        Log.d(TAG, "(addChangeListener) items.size: " + items.size());

        int insertions[] = changeSet.getInsertions();
        if (insertions != null && insertions.length == 1) {  // new scan
          int i = insertions[0];
          Log.d(TAG, "(addChangeListener) inserttion: " + i);
          ProductItem productItem = items.get(i);
          // pass dummy object as container for id and ean
          ProductItem item = new ProductItem();
          item.setId(productItem.getId());
          item.setEan(productItem.getEan());
          // invoke async api call
          startFetching(item);
          // redraw this row (mainly image)
          productItemListAdapter.refresh(item, listView);
        }
      }
    });

    this.productItemListAdapter = new ProductItemListAdapter(
      product.getItems());
    productItemListAdapter.setCallback(this);
  }

  private void initViews() {
    Context context = (Context)this;
    // default: medications
    this.title = context.getString(R.string.medications);

    Toolbar toolbar = (Toolbar)findViewById(R.id.toolbar);
    toolbar.setTitle(title);
    setSupportActionBar(toolbar);

    this.drawerLayout = (DrawerLayout)findViewById(R.id.drawer_layout);
    this.drawerToggle = new ActionBarDrawerToggle(
      this, drawerLayout, R.string.drawer_open, R.string.drawer_close) {

      public void onDrawerOpened(View view) {
        super.onDrawerOpened(view);
        getSupportActionBar().setTitle(title);
        invalidateOptionsMenu(); // onPrepareOptionsMenu
      }

      public void onDrawerClosed(View view) {
        super.onDrawerClosed(view);
        // TODO: update title
        getSupportActionBar().setTitle(title);
        invalidateOptionsMenu();  // onPrepareOptionsMenu
      }
    };
    drawerToggle.syncState();
    drawerLayout.addDrawerListener(drawerToggle);

    ActionBar actionBar = getSupportActionBar();
    actionBar.setDisplayHomeAsUpEnabled(true);
    actionBar.setHomeButtonEnabled(true);

    this.listView = (ListView)findViewById(R.id.list_view);
    listView.setAdapter(productItemListAdapter);
    listView.setOnItemClickListener(this);

    NavigationView navigationView = (NavigationView)findViewById(
      R.id.navigation_view);
    navigationView.setNavigationItemSelectedListener(
      new NavigationView.OnNavigationItemSelectedListener() {

        @Override
        public boolean onNavigationItemSelected(MenuItem menuItem) {
          menuItem.setChecked(true);
          drawerLayout.closeDrawers();
          Toast.makeText(
            MainActivity.this, menuItem.getTitle(), Toast.LENGTH_LONG).show();
          return true;
        }
    });

    FloatingActionButton fab = (FloatingActionButton)findViewById(R.id.fab);
    fab.setOnClickListener(new View.OnClickListener() {

      @Override
      public void onClick(View view) {
        Intent intent = new Intent(
          MainActivity.this, BarcodeCaptureActivity.class);
        intent.putExtra(Constant.kAutoFocus, true);
        intent.putExtra(Constant.kUseFlash, true);
        startActivityForResult(intent, Constant.RC_BARCODE_CAPTURE);
      }
    });
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    // TODO: set options menu by selected item (drawer)
    return super.onPrepareOptionsMenu(menu);
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.menu_main, menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    int id = item.getItemId();

    switch (id) {
      case android.R.id.home:
        drawerLayout.openDrawer(GravityCompat.START);
        return true;
      case R.id.settings:
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
        return true;
    }

    return super.onOptionsItemSelected(item);
  }

  @Override
  protected void onActivityResult(
    int requestCode, int resultCode, Intent data) {

    if (requestCode == Constant.RC_BARCODE_CAPTURE) {
      // get result from barcode reader
      if (resultCode == CommonStatusCodes.SUCCESS) {
        if (data != null) {
          Barcode barcode = data.getParcelableExtra(Constant.kBarcode);
          String filepath = data.getStringExtra(Constant.kFilepath);
          Log.d(TAG, "(onActivityResult) filepath: " + filepath);

          if (barcode.displayValue.length() == 13) {
            // ProductItem's Barcode
            ProductItem.Barcode barcode_ = new ProductItem.Barcode();
            barcode_.setValue(barcode.displayValue);
            barcode_.setFilepath(filepath);
            // save record into realm (next: changeset listener)
            addProductItem(barcode_);
          }
        } else {
          Log.d(TAG, "(onActivityResult) Barcode not found");
        }
      } else {
        Log.d(
          TAG,
          String.format(
            getString(R.string.barcode_error),
            CommonStatusCodes.getStatusCodeString(resultCode))
        );
      }
    } else {
      super.onActivityResult(requestCode, resultCode, data);
    }
  }

  private void addProductItem(final ProductItem.Barcode barcode) {
    realm.executeTransaction(new Realm.Transaction() {

      @Override
      public void execute(Realm realm_) {
        ProductItem.createFromBarcodeIntoSource(
          realm_, barcode, product);
      }
    });
  }

  // -- AdapterView.OnItemClickListener

  @Override
  public void onItemClick(
    AdapterView<?> parent, View view, int position, long id) {

    ProductItem productItem = realm.where(ProductItem.class)
      .equalTo("id", id).findFirst();
    if (productItem == null || productItem.getEan() == null) {  // unexpected
      return;
    }
    if (productItem.getEan().equals("EAN 13")) {  // place holder cell
      return;
    }
    // WebView reads type and lang from shared preferences
    // So, just puts arguments here.
    Intent intent = new Intent(this, WebViewActivity.class);
    intent.putExtra(Constant.kEan, productItem.getEan());
    intent.putExtra(Constant.kReg, productItem.getReg());
    intent.putExtra(Constant.kSeq, productItem.getSeq());
    startActivity(intent);

    overridePendingTransition(R.anim.slide_leave,
                              R.anim.slide_enter);
  }

  // -- ProductItemListAdapter.DeleteListener

  @Override
  public void delete(long itemId) {
    // should check sourceType of Product?
    deleteProductItem(itemId);
  }

  private void deleteProductItem(long productItemId) {
    final long id = productItemId;
    realm.executeTransaction(new Realm.Transaction() {

      @Override
      public void execute(Realm realm_) {
        ProductItem productItem = realm_.where(ProductItem.class)
          .equalTo("id", id).findFirst();
        if (productItem != null) {
          productItem.deleteFromRealm();
        }
      }
    });
    // TODO: is it necessary?
    // listView.invalidateViews();
  }

  private void startFetching(ProductItem productItem) {
    if (!fetching && productItemDataFetcher != null) {
      productItemDataFetcher.invokeFetch(productItem);
      this.fetching = true;
    }
  }

  // -- ProductItemDataFetchFragment.FetchCallback

  @Override
  public void updateFromFetch(
    ProductItemDataFetchFragment.FetchResult result) {
    if (result == null) {
      return;
    }
    if (result.errorMessage != null) {
      alertDialog("", result.errorMessage);
    } else if (result.itemMap != null) {
      final long id = result.itemId;
      Log.d(TAG, "(updateFromFetch) resut.itemId: " + id);

      final HashMap<String, String> properties = result.itemMap;

      // realm is not transferred via background async task
      Realm realm_ = Realm.getDefaultInstance();
      try {
        final ProductItem productItem = realm_.where(ProductItem.class)
          .equalTo("id", id).findFirst();

        if (productItem == null) {
          return;
        }
        productItem.addChangeListener(new RealmChangeListener<ProductItem>() {
          @Override
          public void onChange(ProductItem productItem_) {
            if (productItem_ == null || !productItem_.isValid()) {
              return;
            }
            // only once (remove self)
            productItem_.removeAllChangeListeners();
            if (productItem_.getName() != null &&
                productItem_.getSize() != null) {
              Log.d(TAG,
                "(updateFromFetch) productItem.name: " + productItem_.getName());
              // notify result to user
              // TODO: replace with translated string
              String title = "Generika.cc sagt";
              String message = productItem_.toMessage();
              alertDialog(title, message);
            }
          }
        });
        realm_.executeTransaction(new Realm.Transaction() {

          @Override
          public void execute(Realm realm_) {
            try { // update properties (map) from api fetch result
              if (productItem.isValid()) {
                productItem.updateProperties(properties);
              }
            } catch (Exception e) {
              Log.d(TAG, "(updateFromFetch) Update error: " + e.getMessage());
            }
          }
        });
      } finally {
        realm_.close();
      }
    }
  }

  @Override
  public NetworkInfo getActiveNetworkInfo() {
    // It seems that this cast is not redundant :'(
    ConnectivityManager connectivityManager =
      (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
    NetworkInfo networkinfo = connectivityManager.getActiveNetworkInfo();
    return networkinfo;
  }

  @Override
  public void onProgressUpdate(int progressCode, int percentComplete) {
    // pass
  }

  @Override
  public void finishFetching() {
    this.fetching = false;
    if (productItemDataFetcher != null) {
      productItemDataFetcher.cancelFetch();
    }
  }

  private void alertDialog(String title, String message) {
    Context context = (Context)this;
    AlertDialog.Builder builder = new AlertDialog.Builder(context);
    builder.setTitle(title);
    builder.setMessage(message);
    builder.setCancelable(true);
    builder.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
      public void onClick(DialogInterface dialog, int _id) {
        dialog.cancel();
      }
    });
    AlertDialog alert = builder.create();
    alert.show();
  }
}

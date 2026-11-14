<?php
/**
 * Configures a fresh WooCommerce install as a minimal Polish store and prints one JSON line:
 *   {"product_id":N,"checkout":"classic|block","cod":true}
 * Run with: wp eval-file setup-store.php <classic|block>
 * Everything here uses public WooCommerce APIs / options so it works across the versions we test.
 */
$checkout = isset( $args[0] ) ? $args[0] : 'block';

update_option( 'woocommerce_default_country', 'PL' );
update_option( 'woocommerce_currency', 'PLN' );
update_option( 'woocommerce_allowed_countries', 'specific' );
update_option( 'woocommerce_specific_allowed_countries', array( 'PL' ) );
update_option( 'woocommerce_ship_to_countries', '' );
update_option( 'woocommerce_calc_taxes', 'no' );
update_option( 'woocommerce_coming_soon', 'no' );
update_option( 'woocommerce_store_pages_only', 'no' );
update_option( 'woocommerce_allow_tracking', 'no' );
update_option( 'woocommerce_onboarding_profile', array( 'skipped' => true ) );
update_option( 'woocommerce_task_list_hidden', 'yes' );
update_option( 'woocommerce_enable_guest_checkout', 'yes' );
update_option( 'woocommerce_enable_checkout_login_reminder', 'no' );
update_option( 'blog_public', 0 );

// payment: cash on delivery + bank transfer (the baseline L2 payment methods)
update_option( 'woocommerce_cod_settings', array(
	'enabled'            => 'yes',
	'title'              => 'Płatność przy odbiorze',
	'description'        => 'Zapłać kurierowi.',
	'enable_for_methods' => array(),
	'enable_for_virtual' => 'yes',
) );
update_option( 'woocommerce_bacs_settings', array( 'enabled' => 'yes', 'title' => 'Przelew tradycyjny' ) );

// shipping: one zone (Poland) with a flat rate
$zone = new WC_Shipping_Zone();
$zone->set_zone_name( 'Polska' );
$zone->add_location( 'PL', 'country' );
$zone->save();
$instance = $zone->add_shipping_method( 'flat_rate' );
update_option( 'woocommerce_flat_rate_' . $instance . '_settings', array( 'title' => 'Dostawa kurierem', 'cost' => '12.00', 'tax_status' => 'none' ) );

// one simple product
$p = new WC_Product_Simple();
$p->set_name( 'Produkt testowy' );
$p->set_regular_price( '49.00' );
$p->set_status( 'publish' );
$p->set_catalog_visibility( 'visible' );
$p->set_manage_stock( false );
$p->set_virtual( false );
$p->set_weight( '1' );
$p->save();

// make sure the store pages exist, then choose the checkout flavour for this run
if ( function_exists( 'wc_create_page' ) && ! wc_get_page_id( 'checkout' ) ) {
	WC_Install::create_pages();
}
$cart_id     = wc_get_page_id( 'cart' );
$checkout_id = wc_get_page_id( 'checkout' );
if ( 'classic' === $checkout ) {
	wp_update_post( array( 'ID' => $cart_id, 'post_content' => '<!-- wp:shortcode -->[woocommerce_cart]<!-- /wp:shortcode -->' ) );
	wp_update_post( array( 'ID' => $checkout_id, 'post_content' => '<!-- wp:shortcode -->[woocommerce_checkout]<!-- /wp:shortcode -->' ) );
}
// plain permalinks on purpose: CLI cannot write .htaccess, pages are addressed as /?page_id=N

echo wp_json_encode( array(
	'product_id' => $p->get_id(),
	'checkout'   => $checkout,
	'cart_id'    => $cart_id,
	'checkout_id' => $checkout_id,
	'cod'        => true,
	'woo'        => defined( 'WC_VERSION' ) ? WC_VERSION : null,
) ) . "\n";

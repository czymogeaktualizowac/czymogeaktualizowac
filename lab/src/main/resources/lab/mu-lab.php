<?php
/**
 * Plugin Name: Lab helpers (mu-plugin of the czymogeaktualizowac lab; never ships to a real store)
 */
// no real e-mail leaves the lab
add_filter( 'pre_wp_mail', '__return_true' );
// keep WooCommerce quiet: no marketplace suggestions, no setup wizard redirect, no tracking
add_filter( 'woocommerce_allow_marketplace_suggestions', '__return_false' );
add_filter( 'woocommerce_enable_setup_wizard', '__return_false' );
add_filter( 'woocommerce_admin_disabled', '__return_true' );

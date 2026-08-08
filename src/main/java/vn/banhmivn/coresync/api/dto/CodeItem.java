package vn.banhmivn.coresync.api.dto;

import com.google.gson.annotations.SerializedName;

/**
 * Một dòng phần thưởng của Giftcode — khớp {@code CodeRedeemItem} bên website.
 *
 * <p>product_type: {@code rank} | {@code point} | {@code land} | {@code crate} | {@code item}
 * (land → GriefPrevention claim blocks; crate/item → item đã bind trong items.yml).
 */
public class CodeItem {

    @SerializedName("product_type")
    private final String productType;

    @SerializedName("product_name")
    private final String productName;

    private final int qty;

    public CodeItem(String productType, String productName, int qty) {
        this.productType = productType;
        this.productName = productName;
        this.qty = qty;
    }

    public String getProductType() {
        return productType;
    }

    public String getProductName() {
        return productName;
    }

    public int getQty() {
        return qty;
    }

    @Override
    public String toString() {
        return qty + "x " + productType + "(" + productName + ")";
    }
}

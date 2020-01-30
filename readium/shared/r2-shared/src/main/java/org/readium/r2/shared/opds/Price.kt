/*
 * Module: r2-shared-kotlin
 * Developers: Aferdita Muriqi, Clément Baumann, Mickaël Menu
 *
 * Copyright (c) 2020. Readium Foundation. All rights reserved.
 * Use of this source code is governed by a BSD-style license which is detailed in the
 * LICENSE file present in the project repository where this source code is maintained.
 */

package org.readium.r2.shared.opds

import org.json.JSONObject
import org.readium.r2.shared.JSONable
import org.readium.r2.shared.Warning
import org.readium.r2.shared.WarningLogger
import org.readium.r2.shared.extensions.optNullableString
import org.readium.r2.shared.extensions.optPositiveDouble
import java.io.Serializable

/**
 * The price of a publication in an OPDS link.
 *
 * https://drafts.opds.io/schema/properties.schema.json
 *
 * @param currency Currency for the price, eg. EUR.
 * @param value Price value, should only be used for display purposes, because of precision issues
 *     inherent with Double and the JSON parsing.
 */
data class Price(
    val currency: String,
    val value: Double
) : JSONable, Serializable {

    /**
     * Serializes an [Price] to its JSON representation.
     */
    override fun toJSON() = JSONObject().apply {
        put("currency", currency)
        put("value", value)
    }

    companion object {

        /**
         * Creates an [Price] from its JSON representation.
         * If the price can't be parsed, a warning will be logged with [warnings].
         */
        fun fromJSON(json: JSONObject?, warnings: WarningLogger? = null): Price? {
            val currency = json?.optNullableString("currency")
            val value = json?.optPositiveDouble("value")
            if (currency == null || value == null) {
                warnings?.log(Warning.JsonParsing(Price::class.java, "[currency] and [value] are required", json))
                return null
            }

            return Price(currency = currency, value = value)
        }

    }

}

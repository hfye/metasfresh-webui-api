package de.metas.ui.web.order.sales.pricingConditions.view;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.adempiere.bpartner.BPartnerId;
import org.adempiere.bpartner.service.IBPartnerBL;
import org.adempiere.bpartner.service.IBPartnerDAO;
import org.adempiere.util.Check;
import org.adempiere.util.Services;
import org.slf4j.Logger;

import com.google.common.base.Predicates;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSetMultimap;

import de.metas.inout.IInOutDAO;
import de.metas.lang.Percent;
import de.metas.logging.LogManager;
import de.metas.order.OrderLineId;
import de.metas.pricing.conditions.PriceOverride;
import de.metas.pricing.conditions.PricingConditions;
import de.metas.pricing.conditions.PricingConditionsBreak;
import de.metas.pricing.conditions.PricingConditionsBreakId;
import de.metas.pricing.conditions.PricingConditionsBreakMatchCriteria;
import de.metas.pricing.conditions.PricingConditionsId;
import de.metas.pricing.conditions.service.IPricingConditionsRepository;
import de.metas.product.ProductCategoryId;
import de.metas.product.ProductId;
import de.metas.ui.web.document.filter.DocumentFiltersList;
import de.metas.ui.web.window.datatypes.LookupValue;
import lombok.Builder;
import lombok.NonNull;

/*
 * #%L
 * metasfresh-webui-api
 * %%
 * Copyright (C) 2018 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

class PricingConditionsRowsLoader
{
	// services
	private static final Logger logger = LogManager.getLogger(PricingConditionsRowsLoader.class);
	private final IBPartnerDAO bpartnersRepo = Services.get(IBPartnerDAO.class);
	private final IBPartnerBL bpartnerBL = Services.get(IBPartnerBL.class);
	private final IPricingConditionsRepository pricingConditionsRepo = Services.get(IPricingConditionsRepository.class);
	private final IInOutDAO inoutsRepo = Services.get(IInOutDAO.class);

	private static final Comparator<PricingConditionsRow> ROWS_SORTING = Comparator.<PricingConditionsRow, Integer> comparing(row -> row.isEditable() ? 0 : 1)
			.thenComparing(row -> row.getBpartnerDisplayName())
			.thenComparing(row -> row.isCustomer() ? 0 : 1);

	private final PricingConditionsRowLookups lookups;
	private final PricingConditionsBreaksExtractor pricingConditionsBreaksExtractor;
	private final BasePricingSystemPriceCalculator basePricingSystemPriceCalculator;
	private final DocumentFiltersList filters;
	private final int adClientId;
	private final SourceDocumentLine sourceDocumentLine;

	private ImmutableSetMultimap<PricingConditionsId, PricingConditionsInfo> pricingConditionsInfoById; // lazy
	private final LoadingCache<LastInOutDateRequest, Optional<LocalDate>> lastInOutDates = CacheBuilder.newBuilder()
			.build(new CacheLoader<LastInOutDateRequest, Optional<LocalDate>>()
			{
				@Override
				public Optional<LocalDate> load(final LastInOutDateRequest key)
				{
					final LocalDate lastInOutDate = inoutsRepo.getLastInOutDate(key.getBpartnerId(), key.getProductId(), key.isSOTrx());
					return Optional.ofNullable(lastInOutDate);
				}

			});

	@Builder
	private PricingConditionsRowsLoader(
			@NonNull final PricingConditionsRowLookups lookups,
			@NonNull final PricingConditionsBreaksExtractor pricingConditionsBreaksExtractor,
			@NonNull final BasePricingSystemPriceCalculator basePricingSystemPriceCalculator,
			final DocumentFiltersList filters,
			final int adClientId,
			@Nullable final SourceDocumentLine sourceDocumentLine)
	{
		Check.assumeGreaterThanZero(adClientId, "adClientId");

		this.lookups = lookups;
		this.pricingConditionsBreaksExtractor = pricingConditionsBreaksExtractor;
		this.basePricingSystemPriceCalculator = basePricingSystemPriceCalculator;
		this.filters = filters != null ? filters : DocumentFiltersList.EMPTY;
		this.adClientId = adClientId;
		this.sourceDocumentLine = sourceDocumentLine;
	}

	public PricingConditionsRowData load()
	{
		final List<PricingConditionsRow> rows = getAllPricingConditionsId()
				.stream()
				.flatMap(this::streamMatchingSchemaBreaks)
				.filter(Predicates.notNull())
				.flatMap(this::createPricingConditionsRows)
				.sorted(ROWS_SORTING)
				.collect(ImmutableList.toImmutableList());

		final PricingConditionsRow editableRow = rows.stream()
				.filter(this::isCurrentConditions)
				.findFirst()
				.map(PricingConditionsRow::copyAndChangeToEditable)
				.orElseGet(this::createEditablePricingConditionsRowOrNull);

		return PricingConditionsRowData.builder()
				.editableRow(editableRow)
				.rows(rows)
				.orderLineId(sourceDocumentLine != null ? sourceDocumentLine.getOrderLineId() : null)
				.build()
				.filter(filters);
	}

	private Set<PricingConditionsId> getAllPricingConditionsId()
	{
		return getPricingConditionsInfosIndexedById().keySet();
	}

	private Set<PricingConditionsInfo> getPricingConditionsInfos(final PricingConditionsId pricingConditionId)
	{
		return getPricingConditionsInfosIndexedById().get(pricingConditionId);
	}

	private ImmutableSetMultimap<PricingConditionsId, PricingConditionsInfo> getPricingConditionsInfosIndexedById()
	{
		if (pricingConditionsInfoById == null)
		{
			final Stream<PricingConditionsInfo> vendorPricingConditions = streamPricingConditionsInfos(/* isSOTrx */false);
			final Stream<PricingConditionsInfo> customerPricingConditions = streamPricingConditionsInfos(/* isSOTrx */true);

			pricingConditionsInfoById = Stream.concat(vendorPricingConditions, customerPricingConditions)
					.collect(ImmutableSetMultimap.toImmutableSetMultimap(PricingConditionsInfo::getPricingConditionsId, Function.identity()));
		}
		return pricingConditionsInfoById;
	}

	private Stream<PricingConditionsInfo> streamPricingConditionsInfos(final boolean isSOTrx)
	{
		final Map<BPartnerId, Integer> discountSchemaIdsByBPartnerId = bpartnersRepo.retrieveAllDiscountSchemaIdsIndexedByBPartnerId(adClientId, isSOTrx);

		return discountSchemaIdsByBPartnerId.keySet()
				.stream()
				.map(lookups::lookupBPartner)
				.filter(Predicates.notNull())
				.map(bpartner -> PricingConditionsInfo.builder()
						.bpartner(bpartner)
						.pricingConditionsId(getPricingConditionsIdByBPartner(bpartner, discountSchemaIdsByBPartnerId))
						.isSOTrx(isSOTrx)
						.build());
	}

	private static final PricingConditionsId getPricingConditionsIdByBPartner(final LookupValue bpartner, final Map<BPartnerId, Integer> discountSchemaIdsByBPartnerId)
	{
		final BPartnerId bpartnerId = BPartnerId.ofRepoId(bpartner.getIdAsInt());
		final Integer discountSchemaId = discountSchemaIdsByBPartnerId.get(bpartnerId);
		if (discountSchemaId == null)
		{
			return null;
		}

		return PricingConditionsId.ofDiscountSchemaId(discountSchemaId);
	}

	private Stream<PricingConditionsBreak> streamMatchingSchemaBreaks(final PricingConditionsId pricingConditionsId)
	{
		final PricingConditions pricingConditions = pricingConditionsRepo.getPricingConditionsById(pricingConditionsId);
		return pricingConditionsBreaksExtractor.streamPricingConditionsBreaks(pricingConditions);
	}

	private Stream<PricingConditionsRow> createPricingConditionsRows(final PricingConditionsBreak pricingConditionsBreak)
	{
		return getPricingConditionsInfos(pricingConditionsBreak.getPricingConditionsId())
				.stream()
				.map(pricingConditionsInfo -> createPricingConditionsRow(pricingConditionsBreak, pricingConditionsInfo));
	}

	private PricingConditionsRow createPricingConditionsRow(final PricingConditionsBreak pricingConditionsBreak, final PricingConditionsInfo pricingConditionsInfo)
	{
		return PricingConditionsRow.builder()
				.lookups(lookups)
				.editable(false)
				//
				.bpartner(pricingConditionsInfo.getBpartner())
				.customer(pricingConditionsInfo.isSOTrx())
				//
				.pricingConditionsId(pricingConditionsInfo.getPricingConditionsId())
				.pricingConditionsBreak(pricingConditionsBreak)
				.dateLastInOut(getLastInOutDate(pricingConditionsInfo.getBPartnerId(), pricingConditionsInfo.isSOTrx(), pricingConditionsBreak))
				.basePricingSystemPriceCalculator(basePricingSystemPriceCalculator)
				//
				.build();
	}

	private boolean isCurrentConditions(final PricingConditionsRow row)
	{
		if (sourceDocumentLine == null)
		{
			return false;
		}
		else if (sourceDocumentLine.getPricingConditionsBreakId() != null)
		{
			return sourceDocumentLine.getPricingConditionsBreakId().equals(row.getPricingConditionsBreak().getId());
		}
		else
		{
			return Objects.equals(row.getBpartnerId(), sourceDocumentLine.getBpartnerId())
					&& (sourceDocumentLine.isSOTrx() ? row.isCustomer() : row.isVendor());
		}
	}

	private PricingConditionsRow createEditablePricingConditionsRowOrNull()
	{
		if (sourceDocumentLine == null)
		{
			return null;
		}

		final int discountSchemaId = bpartnerBL.getDiscountSchemaId(sourceDocumentLine.getBpartnerId(), sourceDocumentLine.isSOTrx());
		final PricingConditionsId pricingConditionsId = PricingConditionsId.ofDiscountSchemaIdOrNull(discountSchemaId);

		final PricingConditionsBreak pricingConditionsBreak = PricingConditionsBreak.builder()
				.id(null) // N/A
				.matchCriteria(PricingConditionsBreakMatchCriteria.builder()
						.breakValue(BigDecimal.ZERO)
						.productId(sourceDocumentLine.getProductId())
						.productCategoryId(sourceDocumentLine.getProductCategoryId())
						.build())
				.priceOverride(PriceOverride.fixedPrice(sourceDocumentLine.getPriceEntered()))
				.paymentTermId(sourceDocumentLine.getPaymentTermId())
				.discount(sourceDocumentLine.getDiscount())
				.dateCreated(null) // N/A
				.build();

		return PricingConditionsRow.builder()
				.lookups(lookups)
				.editable(true)
				//
				.bpartner(lookups.lookupBPartner(sourceDocumentLine.getBpartnerId()))
				.customer(sourceDocumentLine.isSOTrx())
				//
				.pricingConditionsId(pricingConditionsId)
				.pricingConditionsBreak(pricingConditionsBreak)
				.basePricingSystemPriceCalculator(basePricingSystemPriceCalculator)
				//
				.dateLastInOut(getLastInOutDate(sourceDocumentLine.getBpartnerId(), sourceDocumentLine.isSOTrx(), pricingConditionsBreak))
				//
				.build();
	}

	private LocalDate getLastInOutDate(final BPartnerId bpartnerId, final boolean isSOTrx, final PricingConditionsBreak pricingConditionsBreak)
	{
		final ProductId productId = pricingConditionsBreak.getMatchCriteria().getProductId();
		if (productId == null)
		{
			return null;
		}

		final LastInOutDateRequest request = LastInOutDateRequest.builder()
				.bpartnerId(bpartnerId)
				.productId(productId)
				.isSOTrx(isSOTrx)
				.build();

		try
		{
			return lastInOutDates.get(request).orElse(null);
		}
		catch (ExecutionException ex)
		{
			logger.warn("Failed fetching last InOut date for {}. Returning null.", request, ex);
			return null;
		}
	}

	@lombok.Value
	@lombok.Builder
	private static class PricingConditionsInfo
	{
		@lombok.NonNull
		PricingConditionsId pricingConditionsId;
		@lombok.NonNull
		LookupValue bpartner;
		boolean isSOTrx;

		public BPartnerId getBPartnerId()
		{
			return BPartnerId.ofRepoId(bpartner.getIdAsInt());
		}
	}

	@FunctionalInterface
	public static interface PricingConditionsBreaksExtractor
	{
		Stream<PricingConditionsBreak> streamPricingConditionsBreaks(PricingConditions pricingConditions);
	}

	@lombok.Value
	@lombok.Builder
	public static final class SourceDocumentLine
	{
		OrderLineId orderLineId;
		boolean isSOTrx;

		BPartnerId bpartnerId;

		ProductId productId;
		ProductCategoryId productCategoryId;

		BigDecimal priceEntered;

		@lombok.Builder.Default
		Percent discount = Percent.ZERO;

		int paymentTermId;

		PricingConditionsBreakId pricingConditionsBreakId;
	}

	@lombok.Value
	@lombok.Builder
	private static final class LastInOutDateRequest
	{
		@NonNull
		BPartnerId bpartnerId;
		@NonNull
		ProductId productId;
		boolean isSOTrx;
	}

	//
	//
	//
	//
	//

	public static class PricingConditionsRowsLoaderBuilder
	{
		public PricingConditionsRowData load()
		{
			return build().load();
		}
	}
}

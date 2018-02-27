package org.prebid.server.bidder.appnexus;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iab.openrtb.request.Banner;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Imp;
import io.vertx.core.json.Json;
import lombok.AllArgsConstructor;
import lombok.Value;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.prebid.server.auction.model.AdUnitBid;
import org.prebid.server.auction.model.AdapterRequest;
import org.prebid.server.auction.model.PreBidRequestContext;
import org.prebid.server.bidder.BidderName;
import org.prebid.server.bidder.OpenrtbAdapter;
import org.prebid.server.bidder.appnexus.model.AppnexusImpExt;
import org.prebid.server.bidder.appnexus.model.AppnexusImpExtAppnexus;
import org.prebid.server.bidder.appnexus.model.AppnexusKeyVal;
import org.prebid.server.bidder.appnexus.model.AppnexusParams;
import org.prebid.server.bidder.model.AdUnitBidWithParams;
import org.prebid.server.bidder.model.AdapterHttpRequest;
import org.prebid.server.bidder.model.ExchangeCall;
import org.prebid.server.exception.PreBidException;
import org.prebid.server.proto.request.PreBidRequest;
import org.prebid.server.proto.response.Bid;
import org.prebid.server.proto.response.MediaType;
import org.prebid.server.usersyncer.Usersyncer;
import org.prebid.server.util.HttpUtil;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * AppNexus {@link org.prebid.server.bidder.Adapter} implementation.
 * <p>
 * Maintainer email: <a href="mailto:info@prebid.org">info@prebid.org</a>
 */
public class AppnexusAdapter extends OpenrtbAdapter {

    private static final String NAME = BidderName.appnexus.name();

    private static final Set<MediaType> ALLOWED_MEDIA_TYPES = Collections.unmodifiableSet(
            EnumSet.of(MediaType.banner, MediaType.video));

    private static final int AD_POSITION_ABOVE_THE_FOLD = 1; // openrtb.AdPosition.AdPositionAboveTheFold
    private static final int AD_POSITION_BELOW_THE_FOLD = 3; // openrtb.AdPosition.AdPositionBelowTheFold

    private final String endpointUrl;

    public AppnexusAdapter(Usersyncer usersyncer, String endpointUrl) {
        super(usersyncer);
        this.endpointUrl = HttpUtil.validateUrl(Objects.requireNonNull(endpointUrl));
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<AdapterHttpRequest> makeHttpRequests(AdapterRequest adapterRequest,
                                                     PreBidRequestContext preBidRequestContext) {
        final BidRequestWithUrl bidRequestWithUrl = createBidRequest(endpointUrl, adapterRequest, preBidRequestContext);
        final AdapterHttpRequest httpRequest = AdapterHttpRequest.of(bidRequestWithUrl.getEndpointUrl(), headers(),
                bidRequestWithUrl.getBidRequest());
        return Collections.singletonList(httpRequest);
    }

    private BidRequestWithUrl createBidRequest(String endpointUrl, AdapterRequest adapterRequest,
                                               PreBidRequestContext preBidRequestContext) {
        final List<AdUnitBid> adUnitBids = adapterRequest.getAdUnitBids();

        validateAdUnitBidsMediaTypes(adUnitBids);

        final List<AdUnitBidWithParams<AppnexusParams>> adUnitBidsWithParams = createAdUnitBidsWithParams(adUnitBids);
        final List<Imp> imps = makeImps(adUnitBidsWithParams, preBidRequestContext);
        validateImps(imps);

        final PreBidRequest preBidRequest = preBidRequestContext.getPreBidRequest();
        final BidRequest bidRequest = BidRequest.builder()
                .id(preBidRequest.getTid())
                .at(1)
                .tmax(preBidRequest.getTimeoutMillis())
                .imp(imps)
                .app(preBidRequest.getApp())
                .site(makeSite(preBidRequestContext))
                .device(deviceBuilder(preBidRequestContext).build())
                .user(makeUser(preBidRequestContext))
                .source(makeSource(preBidRequestContext))
                .build();

        return BidRequestWithUrl.of(bidRequest, endpointUrl(endpointUrl, adUnitBidsWithParams));
    }

    private static List<AdUnitBidWithParams<AppnexusParams>> createAdUnitBidsWithParams(List<AdUnitBid> adUnitBids) {
        return adUnitBids.stream()
                .map(adUnitBid -> AdUnitBidWithParams.of(adUnitBid, parseAndValidateParams(adUnitBid)))
                .collect(Collectors.toList());
    }

    private static AppnexusParams parseAndValidateParams(AdUnitBid adUnitBid) {
        final ObjectNode paramsNode = adUnitBid.getParams();
        if (paramsNode == null) {
            throw new PreBidException("Appnexus params section is missing");
        }

        final AppnexusParams params;
        try {
            params = Json.mapper.convertValue(paramsNode, AppnexusParams.class);
        } catch (IllegalArgumentException e) {
            // a weird way to pass parsing exception
            throw new PreBidException(e.getMessage(), e.getCause());
        }

        final Integer placementId = params.getPlacementId();
        if (placementId == null || Objects.equals(placementId, 0)
                && (StringUtils.isEmpty(params.getInvCode()) || StringUtils.isEmpty(params.getMember()))) {
            throw new PreBidException("No placement or member+invcode provided");
        }
        return params;
    }

    private static List<Imp> makeImps(List<AdUnitBidWithParams<AppnexusParams>> adUnitBidsWithParams,
                                      PreBidRequestContext preBidRequestContext) {
        return adUnitBidsWithParams.stream()
                .flatMap(adUnitBidWithParams -> makeImpsForAdUnitBid(adUnitBidWithParams, preBidRequestContext))
                .collect(Collectors.toList());
    }

    private static Stream<Imp> makeImpsForAdUnitBid(AdUnitBidWithParams<AppnexusParams> adUnitBidWithParams,
                                                    PreBidRequestContext preBidRequestContext) {
        final AdUnitBid adUnitBid = adUnitBidWithParams.getAdUnitBid();
        final AppnexusParams params = adUnitBidWithParams.getParams();

        return allowedMediaTypes(adUnitBid, ALLOWED_MEDIA_TYPES).stream()
                .map(mediaType -> impBuilderWithMedia(mediaType, adUnitBid, params)
                        .id(adUnitBid.getAdUnitCode())
                        .instl(adUnitBid.getInstl())
                        .secure(preBidRequestContext.getSecure())
                        .tagid(StringUtils.stripToNull(params.getInvCode()))
                        .bidfloor(bidfloor(params))
                        .ext(Json.mapper.valueToTree(makeImpExt(params)))
                        .build());
    }

    private static Imp.ImpBuilder impBuilderWithMedia(MediaType mediaType, AdUnitBid adUnitBid, AppnexusParams params) {
        final Imp.ImpBuilder impBuilder = Imp.builder();

        switch (mediaType) {
            case video:
                impBuilder.video(videoBuilder(adUnitBid).build());
                break;
            case banner:
                impBuilder.banner(makeBanner(adUnitBid, params.getPosition()));
                break;
            default:
                // unknown media type, just skip it
        }
        return impBuilder;
    }

    private static Banner makeBanner(AdUnitBid adUnitBid, String position) {
        final Banner.BannerBuilder bannerBuilder = bannerBuilder(adUnitBid);

        if (Objects.equals(position, "above")) {
            bannerBuilder.pos(AD_POSITION_ABOVE_THE_FOLD);
        } else if (Objects.equals(position, "below")) {
            bannerBuilder.pos(AD_POSITION_BELOW_THE_FOLD);
        }

        return bannerBuilder.build();
    }

    private static Float bidfloor(AppnexusParams params) {
        final BigDecimal reserve = params.getReserve();
        return reserve != null && reserve.compareTo(BigDecimal.ZERO) > 0
                ? reserve.floatValue() // TODO: we need to factor in currency here if non-USD
                : null;
    }

    private static AppnexusImpExt makeImpExt(AppnexusParams params) {
        return AppnexusImpExt.of(
                AppnexusImpExtAppnexus.of(
                        params.getPlacementId(), makeKeywords(params), params.getTrafficSourceCode()));
    }

    private static String makeKeywords(AppnexusParams params) {
        final List<AppnexusKeyVal> keywords = params.getKeywords();
        if (CollectionUtils.isEmpty(keywords)) {
            return null;
        }

        final List<String> kvs = new ArrayList<>();
        for (AppnexusKeyVal keyVal : keywords) {
            final String key = keyVal.getKey();
            final List<String> values = keyVal.getValue();
            if (values == null || values.isEmpty()) {
                kvs.add(key);
            } else {
                for (String value : values) {
                    kvs.add(String.format("%s=%s", key, value));
                }
            }
        }

        return kvs.stream().collect(Collectors.joining(","));
    }

    private static String endpointUrl(String endpointUrl,
                                      List<AdUnitBidWithParams<AppnexusParams>> adUnitBidsWithParams) {
        return adUnitBidsWithParams.stream()
                .map(AdUnitBidWithParams::getParams)
                .filter(params -> StringUtils.isNotEmpty(params.getInvCode())
                        && StringUtils.isNotBlank(params.getMember()))
                .reduce((first, second) -> second)
                .map(params -> String.format("%s%s", endpointUrl, String.format("?member_id=%s", params.getMember())))
                .orElse(endpointUrl);
    }

    @Override
    public List<Bid.BidBuilder> extractBids(AdapterRequest adapterRequest, ExchangeCall exchangeCall) {
        return responseBidStream(exchangeCall.getBidResponse())
                .map(bid -> toBidBuilder(bid, adapterRequest, exchangeCall.getBidRequest()))
                .collect(Collectors.toList());
    }

    private static Bid.BidBuilder toBidBuilder(com.iab.openrtb.response.Bid bid, AdapterRequest adapterRequest,
                                               BidRequest bidRequest) {
        final AdUnitBid adUnitBid = lookupBid(adapterRequest.getAdUnitBids(), bid.getImpid());
        return Bid.builder()
                .bidder(adUnitBid.getBidderCode())
                .bidId(adUnitBid.getBidId())
                .code(bid.getImpid())
                .price(bid.getPrice())
                .adm(bid.getAdm())
                .creativeId(bid.getCrid())
                .mediaType(mediaTypeFor(bid.getImpid(), bidRequest.getImp()))
                .width(bid.getW())
                .height(bid.getH())
                .dealId(bid.getDealid())
                .nurl(bid.getNurl());
    }

    private static MediaType mediaTypeFor(String impId, List<Imp> imps) {
        MediaType mediaType = MediaType.banner;
        if (imps != null) {
            for (Imp imp : imps) {
                if (Objects.equals(imp.getId(), impId)) {
                    if (imp.getVideo() != null) {
                        mediaType = MediaType.video;
                    }
                    break;
                }
            }
        }
        return mediaType;
    }

    @AllArgsConstructor(staticName = "of")
    @Value
    private static final class BidRequestWithUrl {

        BidRequest bidRequest;

        String endpointUrl;
    }
}

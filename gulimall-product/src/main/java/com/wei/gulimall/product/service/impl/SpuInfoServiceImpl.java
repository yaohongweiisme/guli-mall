package com.wei.gulimall.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wei.common.to.SkuReductionTo;
import com.wei.common.to.SpuBoundTo;
import com.wei.common.utils.R;
import com.wei.gulimall.product.entity.*;
import com.wei.gulimall.product.feign.CouponFeignService;
import com.wei.gulimall.product.service.*;
import com.wei.gulimall.product.vo.*;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wei.common.utils.PageUtils;
import com.wei.common.utils.Query;

import com.wei.gulimall.product.dao.SpuInfoDao;
import org.springframework.transaction.annotation.Transactional;


@Service("spuInfoService")
public class SpuInfoServiceImpl extends ServiceImpl<SpuInfoDao, SpuInfoEntity> implements SpuInfoService {
    @Autowired
    private SpuInfoDescService spuInfoDescService;
    @Autowired
    private SpuImagesService spuImagesService;
    @Autowired
    private AttrService attrService;
    @Autowired
    private ProductAttrValueService productAttrValueService;
    @Autowired
    private SkuInfoService skuInfoService;
    @Autowired
    private SkuImagesService skuImagesService;
    @Autowired
    private SkuSaleAttrValueService skuSaleAttrValueService;
    @Autowired
    private CouponFeignService couponFeignService;
    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<SpuInfoEntity> page = this.page(
                new Query<SpuInfoEntity>().getPage(params),
                new QueryWrapper<SpuInfoEntity>()
        );

        return new PageUtils(page);
    }
    @Transactional
    @Override
    public void saveSpuInfo(SpuSaveVo vo) {
        //保存spu基本信息，pms_spu_info
        SpuInfoEntity spuInfoEntity = new SpuInfoEntity();
        BeanUtils.copyProperties(vo,spuInfoEntity);
        this.saveBaseSpuInfo(spuInfoEntity);
        //保存spu的描述图片，pms_spu_info_desc
        List<String> decript = vo.getDecript();
        SpuInfoDescEntity descEntity = new SpuInfoDescEntity();
        descEntity.setSpuId(spuInfoEntity.getId());
        descEntity.setDecript(String.join(",",decript));
        spuInfoDescService.saveSpuInfoDesc(descEntity);
        //保存spu的图片集，pms_spu_images
        List<String> images = vo.getImages();
        spuImagesService.saveImages(spuInfoEntity.getId(),images);
        //保存spu的规格参数，pms_product_attr_value
        List<BaseAttrs> baseAttrs = vo.getBaseAttrs();
        List<ProductAttrValueEntity> productAttrValueEntityList = baseAttrs.stream().map(attr -> {
            ProductAttrValueEntity valueEntity = new ProductAttrValueEntity();
            valueEntity.setAttrId(attr.getAttrId());
            AttrEntity attrEntity = attrService.getById(attr.getAttrId());
            valueEntity.setAttrName(attrEntity.getAttrName());
            valueEntity.setAttrValue(attr.getAttrValues());
            valueEntity.setQuickShow(attr.getShowDesc());
            valueEntity.setSpuId(spuInfoEntity.getId());
            return valueEntity;
        }).collect(Collectors.toList());
        productAttrValueService.saveProductAttrValue(productAttrValueEntityList);
        //5) 保存积分信息，sms_spu_bounds
        Bounds bounds = vo.getBounds();
        SpuBoundTo boundTo = new SpuBoundTo();
        BeanUtils.copyProperties(bounds,boundTo);
        boundTo.setSpuId(spuInfoEntity.getId());
        R r = couponFeignService.saveSpuBounds(boundTo);
        if(r.getCode()!=0){
            log.error("远程保存spu积分信息失败");
        }

        //保存spu对应的所有sku信息
        //1）sku的基本信息，pms_sku_info
        List<Skus> skus = vo.getSkus();
        if(skus.size()!=0 && skus!=null){
            skus.forEach(item->{
                String defaultImage="";
                for (Images image:item.getImages()){
                    if(image.getDefaultImg()==1){
                        defaultImage=image.getImgUrl();
                    }
                }
                SkuInfoEntity skuInfoEntity = new SkuInfoEntity();
                BeanUtils.copyProperties(item,skuInfoEntity);
                skuInfoEntity.setBrandId(spuInfoEntity.getBrandId());
                skuInfoEntity.setCatalogId(spuInfoEntity.getCatalogId());
                skuInfoEntity.setSpuId(spuInfoEntity.getId());
                skuInfoEntity.setSkuDefaultImg(defaultImage);
                skuInfoService.saveSkuInfo(skuInfoEntity);
                Long skuId = skuInfoEntity.getSkuId();
                //2）sku的图片信息，pms_sku_images
                List<SkuImagesEntity> imagesEntities = item.getImages().stream().map(img -> {
                    SkuImagesEntity skuImagesEntity = new SkuImagesEntity();
                    skuImagesEntity.setSkuId(skuId);
                    skuImagesEntity.setImgUrl(img.getImgUrl());
                    skuImagesEntity.setDefaultImg(img.getDefaultImg());
                    return skuImagesEntity;
                }).filter(entity->{
                    //返回false的会被过滤掉
                    return !StringUtils.isEmpty(entity.getImgUrl());
                        })
                        .collect(Collectors.toList());
                skuInfoEntity.setSkuDefaultImg(defaultImage);
                skuImagesService.saveBatch(imagesEntities);
                //3）sku的销售属性信息，pms_sku_sale_attr_value
                List<Attr> attr = item.getAttr();
                List<SkuSaleAttrValueEntity> saleAttrValueEntities = attr.stream().map(a -> {
                    SkuSaleAttrValueEntity saleAttrValue = new SkuSaleAttrValueEntity();
                    BeanUtils.copyProperties(a, saleAttrValue);
                    saleAttrValue.setSkuId(skuId);
                    return saleAttrValue;
                }).collect(Collectors.toList());
                skuSaleAttrValueService.saveBatch(saleAttrValueEntities);
                //4）sku的优惠、满减等信息，跨库操作(gulimall-sms)-> sms_sku_ladder（打折表）-> sms_sku_full_reduction（满减表）-> sms_member_price（会员价格表）
                SkuReductionTo skuReductionTo = new SkuReductionTo();
                BeanUtils.copyProperties(item,skuReductionTo);
                skuReductionTo.setSkuId(skuId);
                if (skuReductionTo.getFullCount()>0 || (skuReductionTo.getFullPrice().compareTo(new BigDecimal("0"))==1)){
                    R r1 = couponFeignService.saveSkuReduction(skuReductionTo);
                    if(r1.getCode()!=0){
                        log.error("远程保存spu优惠卷信息失败");
                    }
                }

            });
        }


    }

    @Override
    public void saveBaseSpuInfo(SpuInfoEntity spuInfoEntity) {
        baseMapper.insert(spuInfoEntity);
    }

    @Override
    public PageUtils queryPageByCondition(Map<String, Object> params) {
        LambdaQueryWrapper<SpuInfoEntity> wrapper = new LambdaQueryWrapper<>();
        String key = (String) params.get("key");
        if(!StringUtils.isEmpty(key)) {
            //and ( xxxxxx or xxxxx)
            wrapper.and( w->{
                w.eq(SpuInfoEntity::getId,key).or().like(SpuInfoEntity::getSpuName,key);
            });
        }
        String status = (String) params.get("status");
        wrapper.eq(!StringUtils.isEmpty(status) ,SpuInfoEntity::getPublishStatus,status);
        String brandId = (String) params.get("brandId");
        wrapper.eq(!StringUtils.isEmpty(brandId) && !"0".equalsIgnoreCase(brandId),SpuInfoEntity::getBrandId,brandId);
        String categoryId = (String) params.get("categoryId");
        wrapper.eq(!StringUtils.isEmpty(categoryId) && !"0".equalsIgnoreCase(categoryId),SpuInfoEntity::getCatalogId,categoryId);

        IPage<SpuInfoEntity> page = this.page(
                new Query<SpuInfoEntity>().getPage(params),
                wrapper
        );

        return new PageUtils(page);
    }


}
package com.lazada.crawler;

import com.lazada.entity.LazadaProduct;

import java.io.IOException;

/**
 * Lazada爬虫主类
 */
public class LazadaCrawler {
    
    private final OxylabsClient oxylabsClient;
    private final LazadaParser parser;
    
    public LazadaCrawler(String username, String password) {
        this.oxylabsClient = new OxylabsClient(username, password);
        this.parser = new LazadaParser();
    }
    
    /**
     * 抓取并解析Lazada商品
     */
    public LazadaProduct crawl(String url) throws IOException {
        // 1. 抓取HTML
        String html = oxylabsClient.fetchHtml(url);
        
        // 2. 解析
        LazadaProduct product = parser.parse(html);
        product.setUrl(url);
        
        return product;
    }
    
    /**
     * 抓取商品（兼容旧接口）
     */
    public LazadaProduct crawlAndSave(String url) throws IOException {
        return crawl(url);
    }
    
    /**
     * 只解析本地HTML文件（测试用）
     */
    public LazadaProduct parseLocalHtml(String filePath) throws IOException {
        String html = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(filePath)));
        return parser.parse(html);
    }
    
    /**
     * 关闭资源
     */
    public void shutdown() {
        oxylabsClient.shutdown();
    }
    
    /**
     * 打印商品信息
     */
    public void printProduct(LazadaProduct product) {
        System.out.println("============================================================");
        System.out.println("Lazada商品信息" + (product.getId() != null ? " (ID: " + product.getId() + ")" : ""));
        System.out.println("============================================================");
        System.out.println("title: " + (product.getTitle() != null ? product.getTitle().substring(0, Math.min(50, product.getTitle().length())) + "..." : "N/A"));
        System.out.println("price: " + product.getPrice());
        System.out.println("originalPrice: " + product.getOriginalPrice());
        System.out.println("discount: " + product.getDiscount());
        System.out.println("dimensions: " + product.getDimensions());
        System.out.println("weight: " + product.getWeight());
        System.out.println("images: " + (product.getImages() != null ? product.getImages().split(",").length + " 张" : "0 张"));
        System.out.println("packingList: " + product.getPackingList());
        System.out.println("description: " + (product.getDescription() != null ? product.getDescription().length() + " 字符" : "0 字符"));
        System.out.println("specifications: " + product.getSpecifications());
        System.out.println("url: " + product.getUrl());
        System.out.println("categoryName: " + product.getCategoryName());
        System.out.println("skuId: " + product.getSkuId());
    }
    
    public static void main(String[] args) {
        // Oxylabs凭证
        String username = "quan8050_hmxyo";
        String password = "Oxylabs_Api2026+";
        
        // 测试URL
        String testUrl = "https://www.lazada.com.ph/products/-i4603417160-s26440518978.html";
        
        LazadaCrawler crawler = new LazadaCrawler(username, password);
        
        try {
            // 抓取
            LazadaProduct product = crawler.crawlAndSave(testUrl);
            
            if (product != null) {
                crawler.printProduct(product);
            }
            
        } catch (IOException e) {
            System.err.println("爬取失败: " + e.getMessage());
            e.printStackTrace();
        } finally {
            crawler.shutdown();
        }
    }
}

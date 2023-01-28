package org.apache.dubbo.demo;

public class DemoServiceStub implements DemoService{

    private  DemoService demoService;

    public DemoServiceStub(DemoService demoService) {
        this.demoService = demoService;
    }

    @Override
    public String sayHello(String name) {
        System.out.println("before say hello");
        String result = demoService.sayHello(name);
        System.out.println("say Hello");
        return result;
    }

    @Override
    public String sayHi(String name) {
        return null;
    }
}

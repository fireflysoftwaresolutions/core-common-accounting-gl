/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.accounting.models.repositories.posting;

import java.util.Objects;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;

/** Keeps the protected owner template separate from the single ordinary Boot template bean. */
public record GlNativePostingResources(R2dbcEntityTemplate template) implements BeanFactoryAware,ApplicationContextAware {
    public GlNativePostingResources {Objects.requireNonNull(template);}
    @Override public void setBeanFactory(BeanFactory factory){template.setBeanFactory(factory);}
    @Override public void setApplicationContext(ApplicationContext context){template.setApplicationContext(context);}
}

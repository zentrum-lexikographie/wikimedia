FROM wikibase/wikibase-bundle:1.36.4-wmde.8

RUN apt-get update && \
    apt-get install unzip && \
    rm -rf /var/lib/apt/lists/*

RUN php -r "copy('https://getcomposer.org/installer', 'composer-setup.php');" && \
    php composer-setup.php && \
    rm composer-setup.php composer.lock && \
    php composer.phar config --no-plugins allow-plugins.composer/installers true

COPY wikibase/extensions/WikibaseLexeme /var/www/html/extensions/WikibaseLexeme

RUN php composer.phar install
